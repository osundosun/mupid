package com.osundosun.mupid.api.report.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.dto.TrackPlayCount;
import com.osundosun.mupid.api.analytics.repository.ListenAnalyticsRepository;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 카탈로그 전체를 보고 만드는 기준선. **취향은 절대값이 아니라 기준선 대비 편차다.**
 *
 * 카탈로그의 90%가 보컬곡이면 보컬곡 비중만으로는 모두가 보컬파가 되고, 재생수가
 * 높은 곡을 들었다는 사실도 그 카탈로그에서 그게 상위 몇 %인지 알아야 뜻이 생긴다.
 *
 * <h2>대중성은 ClickHouse 에서 온다</h2>
 *
 * 원래 MySQL `music.play_count` 를 봤는데 <b>그 컬럼을 올리는 코드가 저장소에 없어
 * 전 곡이 0 이었다.</b> 정렬 배열이 전부 0 이면 어떤 곡을 넣어도 같은 자리가 나와
 * 백분위가 상수가 되고, B축의 대중성 항(가중치 0.30)이 아무도 구분하지 못하는
 * 오프셋으로 전락한다.
 *
 * 그래서 실제 재생 로그(`PLAY_START`)를 센다. 컬럼보다 정확하고, `track_id` 만 쓰므로
 * 곡 메타데이터를 ClickHouse 에 복제할 필요도 없다.
 *
 * <h2>왜 Redis 가 아니라 프로세스 메모리인가</h2>
 *
 * 이 값은 <b>사용자마다 다르지 않다.</b> 그리고 재생수 배열은 곡 수만큼 길어서
 * Redis 에 직렬화해 오가는 비용이 그냥 다시 묻는 것보다 싸지 않다. 인스턴스마다
 * 최대 1시간 어긋날 수 있지만, 백분위 기준선이 그 정도 어긋나는 것은 판정에 영향이 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogBaseline {

    private static final Duration TTL = Duration.ofHours(1);

    /** 분석된 곡이 하나도 없을 때 쓰는 값. 0.5 면 A축 점수가 그대로 0 이 된다. */
    private static final double UNKNOWN_VOCAL_SHARE = 0.5;

    private final MusicRepository musicRepository;
    private final MusicAnalysisRepository analysisRepository;
    private final ListenAnalyticsRepository analytics;

    private final AtomicReference<Snapshot> cached = new AtomicReference<>();

    /**
     * 기준선을 얻는다. 만료됐으면 다시 만든다.
     *
     * 동시에 여러 요청이 만료를 보면 각자 한 번씩 다시 만든다 — 락을 걸지 않는다.
     * 두 번 만들어도 결과가 같고, 락을 걸었다가 저장소가 느릴 때 요청이 줄줄이
     * 묶이는 쪽이 더 나쁘다.
     */
    @Transactional(readOnly = true)
    public Snapshot get() {
        Snapshot now = cached.get();
        if (now != null && !now.isExpired()) {
            return now;
        }

        long catalogSize = musicRepository.countByIsDeletedFalse();
        Double vocalShare = analysisRepository.findVocalShare();

        Map<Integer, Long> playsByTrack = new HashMap<>();
        try {
            for (TrackPlayCount row : analytics.catalogPlayCounts()) {
                playsByTrack.put(row.trackId(), row.plays());
            }
        } catch (DataAccessException e) {
            // 분석 클러스터가 죽어도 기준선 때문에 리포트가 통째로 500 이 되면 안 된다.
            // 대중성만 중립(전부 같은 값)으로 두고 나머지 축은 그대로 판정한다.
            log.warn("카탈로그 대중성 기준선을 읽지 못했다. 대중성 항을 중립으로 둔다.", e);
        }

        Snapshot fresh = new Snapshot(
            sortedWithUnplayedZeros(playsByTrack, catalogSize),
            playsByTrack,
            vocalShare == null ? UNKNOWN_VOCAL_SHARE : vocalShare,
            Instant.now().plus(TTL));
        cached.set(fresh);
        return fresh;
    }

    /**
     * 재생 기록이 있는 곡만 모아 정렬하고, <b>한 번도 재생되지 않은 곡 수만큼 0 을 앞에
     * 채운다.</b>
     *
     * 이걸 빼면 백분위가 통째로 왜곡된다. 1000곡 중 50곡만 재생된 카탈로그에서 배열
     * 길이가 50 이면, 한 번 재생된 곡이 "하위 50곡 중 하나" 가 아니라 "전체 중 하위"
     * 로 읽혀야 하는데 그 반대가 된다.
     */
    private static List<Integer> sortedWithUnplayedZeros(Map<Integer, Long> plays, long catalogSize) {
        List<Integer> counts = new ArrayList<>();
        long unplayed = Math.max(0, catalogSize - plays.size());
        for (long i = 0; i < unplayed; i++) {
            counts.add(0);
        }
        for (Long value : plays.values()) {
            counts.add((int)Math.min(value, Integer.MAX_VALUE));
        }
        Collections.sort(counts);
        return counts;
    }

    /**
     * 한 시점의 카탈로그 기준선.
     *
     * @param playCountsAsc 카탈로그 전 곡의 누적 재생수, 오름차순 (재생 0인 곡 포함)
     * @param playsByTrack  곡 번호 → 누적 재생수. 없으면 0 회다
     * @param vocalShare    분석된 곡 중 보컬이 있는 비중 (0~1)
     */
    public record Snapshot(
        List<Integer> playCountsAsc,
        Map<Integer, Long> playsByTrack,
        double vocalShare,
        Instant expiresAt
    ) {

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        /**
         * 이 곡이 카탈로그에서 몇 번째 백분위로 인기 있는가 (0~1).
         *
         * 재생 기록이 없으면 0 회로 본다 — 아무도 안 들은 곡이라는 뜻이라 맞다.
         */
        public double percentileOfTrack(int musicId) {
            return percentileOf(
                (int)Math.min(playsByTrack.getOrDefault(musicId, 0L), Integer.MAX_VALUE));
        }

        /**
         * 이 재생수가 카탈로그에서 몇 번째 백분위인가 (0~1).
         *
         * <b>"나보다 적게 재생된 곡의 비율" — 동점 구간의 왼쪽 끝을 쓴다.</b>
         *
         * `Collections.binarySearch` 를 쓰면 안 된다. 동점이 여러 개일 때 그중 <b>아무
         * 자리나</b> 돌려주기 때문이다. 재생 0 인 곡이 대부분인 카탈로그에서는 그 값이
         * 0 이 아니라 0 무리의 한가운데(≈0.4)가 되어, <b>아무도 안 들은 곡이 "중간쯤
         * 인기" 로 읽힌다.</b> 곡이 1000개면 대부분이 그 상태다.
         */
        public double percentileOf(int playCount) {
            if (playCountsAsc.isEmpty()) return 0.5;
            int lo = 0;
            int hi = playCountsAsc.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (playCountsAsc.get(mid) < playCount) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return (double)lo / playCountsAsc.size();
        }

    }

}
