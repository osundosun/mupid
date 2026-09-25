package com.osundosun.mupid.api.analytics.sync.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.repository.TrackSectionRepository;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.SectionDigest;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.repository.SectionDigestRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 MySQL 과 ClickHouse <b>레플리카마다</b> 곡별 구간 수·체크섬을 비교해, 어긋난 곡을
 * 미반영 기록에 넣는다 (S15P21A204-451, 설계 §8.2-4). 2026-09-25 에 손으로 한 대조의 자동화다.
 *
 * <h2>왜 필요한가 — 미반영 기록만으로는 부족하다</h2>
 *
 * <p>미반영 기록은 <b>Spring 을 거친 쓰기</b>만 잡는다. 이 대조는 그 밖을 잡는 그물이다 —
 * 레플리카 하나가 디스크를 잃고 다시 붙은 경우, ClickHouse 표를 누가 손으로 고친 경우,
 * 이 기록이 생기기 전에 빠진 곡. 찾기만 하고 고치지는 않는다. 넣어 두면 재시도 작업이
 * MySQL 의 지금 구간으로 교체한다 — 고치는 길은 하나로 둔다.
 *
 * <h2>레플리카를 각각 본다</h2>
 *
 * <p>Spring 은 헤드리스 서비스 하나({@code clickhouse:8123})에 붙어 어느 노드에 닿을지 모른다.
 * {@code clusterAllReplicas} 로 두 레플리카를 따로 읽고 {@code hostName()} 으로 가른다
 * ({@code TrackSectionRepository#digestsByReplica}). 로컬 표에 쓰면 복제로 퍼지지만, 복제가
 * 밀리거나 한쪽이 비면 붙은 노드만 봐서는 모른다.
 *
 * <h2>인스턴스마다 돈다 — Redis 로 선점한다</h2>
 *
 * <p>다른 일일 배치와 같은 방식이다. 두 번 돌아도 틀리지 않지만(넣기만 하고, 넣는 것은
 * 멱등이다) 전 곡 집계로 ClickHouse 를 인스턴스 수만큼 때릴 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackSectionReconciler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 선점 유지 시간. 하루보다 짧아야 다음 날이 자기 선점에 막히지 않는다(다른 배치와 같다). */
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    /** 로그에 곡 번호를 몇 개까지 적을지. 전 곡이 어긋나면 로그 한 줄이 1만 곡이 된다. */
    private static final int LOG_SAMPLE = 20;

    private final SectionDigestRepository mysqlDigests;
    private final TrackSectionRepository clickHouse;
    private final TrackSectionSync sync;
    private final AnalyticsSyncMonitor monitor;
    private final CacheService cacheService;

    /**
     * 매일 04:40 KST. 자정 배치들(00:05 리포트·00:10 재생수)·매시 5분 AI 선호도·정오 모먼트와
     * 겹치지 않고, 03:00 백업 창도 피한다(decision 0011). 전처리 배치가 구간을 올리는 중에
     * 돌아도 틀리지 않는다 — 커밋 직후라 아직 안 간 곡이 불일치로 잡혀도 미반영에 넣는 것은
     * 멱등이고, 재시도가 같은 구간을 한 번 더 쓸 뿐이다.
     */
    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        LocalDate today = LocalDate.now(ZONE);
        if (!cacheService.setIfAbsent(today.toString(), CacheKind.AnalyticsReconcileBatch, LOCK_TTL)) {
            log.info("구간 대조를 다른 인스턴스가 이미 잡았다. date={}", today);
            return;
        }
        reconcile();
    }

    /**
     * 대조하고, 어긋난 곡을 미반영에 넣고, 결과를 지표로 남긴다.
     *
     * <p>ClickHouse 가 안 닿으면(레플리카 하나라도) <b>아무것도 넣지 않는다.</b> 반쪽
     * 결과로는 "없다" 와 "못 읽었다" 를 가르지 못한다. 실패도 결과로 남긴다 — 대시보드가
     * "어제 대조가 안 돌았다" 를 보여 줘야 한다.
     */
    public ReconcileReport reconcile() {
        ReconcileReport report;
        try {
            Map<TrackVersion, SectionDigest> mysql = mysqlDigests.digests();
            Map<String, Map<TrackVersion, SectionDigest>> replicas = clickHouse.digestsByReplica();
            report = compare(mysql, replicas, LocalDateTime.now(ZONE));
        } catch (RuntimeException e) {
            log.warn("구간 대조를 하지 못했다. 미반영 기록은 그대로다.", e);
            report = ReconcileReport.failed(LocalDateTime.now(ZONE), TrackSectionSync.describe(e));
            monitor.recordReconcile(report);
            return report;
        }

        int enqueued = 0;
        for (TrackVersion track : report.mismatchedTracks()) {
            try {
                sync.enqueue(track.musicId(), track.analysisVersion());
                enqueued++;
            } catch (RuntimeException e) {
                log.warn("어긋난 곡을 미반영에 넣지 못했다. musicId={}", track.musicId(), e);
            }
        }

        log.info("구간 대조 완료 — 검사 {}곡 · 불일치 {}곡(미반영에 {}곡) · 레플리카별 {} · "
                + "ClickHouse 에만 {}곡 · 옛 버전 잔여 {}곡{}",
            report.checkedTracks(), report.mismatchCount(), enqueued, report.mismatchByReplica(),
            report.onlyInClickHouse(), report.staleVersions(), sample(report.mismatchedTracks()));
        monitor.recordReconcile(report);
        return report;
    }

    /**
     * 두 쪽 요약을 비교한다. DB 를 보지 않는다 — 판단만 따로 시험하려고 떼어 둔다.
     *
     * <ul>
     *   <li>MySQL 에 있는 (곡, 버전)마다, 레플리카마다: 없거나 수·체크섬이 다르면 불일치.</li>
     *   <li>ClickHouse 에만 있는 (곡, 버전): 그 곡이 MySQL 에 <b>다른 버전으로</b> 있으면 옛 버전
     *       잔여, MySQL 에 아예 없으면 "ClickHouse 에만". 둘 다 교체로 고칠 수 없어 넣지 않는다.</li>
     * </ul>
     *
     * <p>레플리카가 하나도 없으면(표가 없는 환경) MySQL 의 모든 곡이 불일치다 — 빈 결과를
     * "일치" 로 읽지 않는다.
     */
    static ReconcileReport compare(Map<TrackVersion, SectionDigest> mysql,
                                   Map<String, Map<TrackVersion, SectionDigest>> replicas,
                                   LocalDateTime finishedAt) {
        Comparator<TrackVersion> order = Comparator.comparingInt(TrackVersion::musicId)
            .thenComparing(TrackVersion::analysisVersion);
        Set<TrackVersion> mismatched = new TreeSet<>(order);
        Map<String, Integer> byReplica = new LinkedHashMap<>();

        if (replicas.isEmpty()) {
            mismatched.addAll(mysql.keySet());
        }
        for (Map.Entry<String, Map<TrackVersion, SectionDigest>> replica : replicas.entrySet()) {
            int count = 0;
            for (Map.Entry<TrackVersion, SectionDigest> expected : mysql.entrySet()) {
                if (!expected.getValue().equals(replica.getValue().get(expected.getKey()))) {
                    mismatched.add(expected.getKey());
                    count++;
                }
            }
            byReplica.put(replica.getKey(), count);
        }

        Set<Integer> mysqlTracks = new HashSet<>();
        mysql.keySet().forEach(key -> mysqlTracks.add(key.musicId()));
        Set<Integer> onlyInClickHouse = new HashSet<>();
        Set<Integer> staleVersions = new HashSet<>();
        for (Map<TrackVersion, SectionDigest> rows : replicas.values()) {
            for (TrackVersion key : rows.keySet()) {
                if (mysql.containsKey(key)) {
                    continue;
                }
                if (mysqlTracks.contains(key.musicId())) {
                    staleVersions.add(key.musicId());
                } else {
                    onlyInClickHouse.add(key.musicId());
                }
            }
        }

        return new ReconcileReport(finishedAt, mysqlTracks.size(), mismatched, byReplica,
            onlyInClickHouse.size(), staleVersions.size(), null);
    }

    private static String sample(Set<TrackVersion> tracks) {
        if (tracks.isEmpty()) {
            return "";
        }
        return " — 예: " + tracks.stream().limit(LOG_SAMPLE).map(TrackVersion::musicId).toList();
    }

}
