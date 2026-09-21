package com.osundosun.mupid.api.music.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.dto.TrackPlayCount;
import com.osundosun.mupid.api.analytics.repository.ListenAnalyticsRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 곡별 누적 재생수({@code music.play_count})를 매일 채운다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>이 컬럼은 ERD 에 처음부터 있었지만 <b>그것을 올리는 코드가 저장소 어디에도 없었다.</b>
 * 그래서 카탈로그가 통째로 0 이었고, 396 본편에서는 '조회순' 정렬 축을 <b>일부러 만들지
 * 않았다</b> — 값이 한 종류뿐이면 "조회순" 이라고 적어 놓고 순서를 가르지 못한다
 * ({@code MusicSort} 의 이력 절). 그 선행 조건을 이 배치가 채운다.
 *
 * <p>덤으로 취향 리포트 B축(대중성)이 쓰는 {@code CatalogBaseline} 도 같은 값을 보게 된다.
 * 그쪽은 컬럼이 비어 있어서 <b>조회할 때마다</b> ClickHouse 전 기간을 다시 세고 있었다.
 *
 * <h2>원천은 ClickHouse 다 — 새로 짜지 않고 있는 것을 쓴다</h2>
 *
 * <p>{@link ListenAnalyticsRepository#catalogPlayCounts()} 를 <b>그대로</b> 쓴다. 전
 * 사용자·전 기간의 곡별 {@code PLAY_START} 집계이고, MuBTI B축이 이미 같은 쿼리로 같은
 * 질문에 답하고 있다. 여기서 다시 짜면 두 곳이 갈릴 수 있다.
 *
 * <p>전체 재계산을 고른 근거는 {@link #refreshPlayCounts()} 주석에 있다.
 *
 * <h2>인스턴스마다 돈다 — Redis 로 선점한다</h2>
 *
 * <p>{@code ReportDailyBatch}·{@code DailyRecommendationBatch} 와 같은 이유이고 같은
 * 방식이다. 스케줄러는 API 인스턴스 <b>마다</b> 돌기 때문에, 여러 벌 띄우면 같은 집계를
 * 인스턴스 수만큼 하면서 ClickHouse 를 여러 번 때린다.
 *
 * <h2>실패를 어떻게 다루나</h2>
 *
 * <p>세 층으로 나눈다.
 *
 * <ul>
 *   <li><b>집계 실패</b> — ClickHouse 가 안 닿으면 <b>아무것도 바꾸지 않고</b> 끝낸다.
 *       일부만 반영하면 컬럼이 원천과 어긋난 채 하루를 넘긴다. 다음 날 배치가 다시
 *       맞추지만, 그 사이 '조회순' 목록이 절반만 맞는 순서로 나간다.</li>
 *   <li><b>한 곡 실패</b> — 그 곡만 건너뛰고 다음 곡으로 간다. 한 곡 때문에 나머지
 *       전부를 버리면 그날 재생이 가장 많은 곡들이 통째로 지난 값으로 남는다. 건너뛴 수를
 *       로그에 남긴다 — 조용히 넘어가면 "오늘은 재생이 적네" 로만 보인다.</li>
 *   <li><b>카탈로그에 없는 곡</b> — 실패가 아니라 <b>정상</b>이다. 곡이 지워져도 재생
 *       로그에는 남기 때문이다. 따로 세어 로그에 남긴다(에러 로그를 더럽히지 않는다).</li>
 * </ul>
 *
 * <p>한 곡 쓰기가 <b>자기 트랜잭션</b>에서 돈다 — {@code MusicService.updatePlayCount} 가
 * {@code REQUIRES_NEW} 이고, 여기서는 그 <b>프록시를 통해</b> 부른다. 이 클래스 안에
 * 그 메서드를 두고 {@code this} 로 부르면 프록시를 타지 않아 애노테이션이 조용히 무시되고,
 * "한 건 실패해도 다음으로 넘어간다" 가 거짓이 된다(전체가 한 트랜잭션으로 묶여 마지막
 * 한 곡의 실패가 앞의 수천 곡 갱신을 되돌린다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayCountBatch {

    /** {@code ReportServiceImpl} 과 같은 시간대를 쓴다. 배치마다 다르면 자정 경계가 갈린다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 선점 유지 시간.
     *
     * <p>배치가 도는 시간보다 넉넉하되 하루보다는 짧아야 한다. 하루를 넘기면 다음 날
     * 배치가 자기 선점에 막힌다 — 두 선례와 같은 값, 같은 이유다.
     */
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    private final ListenAnalyticsRepository analytics;
    private final MusicService musicService;
    private final CacheService cacheService;

    /**
     * 매일 00:10 KST. 자정 정각이 아닌 이유는 {@code ReportDailyBatch} 와 같다 —
     * {@code kafka_flush_interval_ms}(15초) 때문에 23:59:59 에 들은 이벤트가 ClickHouse 에
     * 닿기 전에 집계하면 그만큼 빠진다.
     *
     * <p>리포트 배치(00:05)보다 뒤에 둔다. 둘이 같은 ClickHouse 를 때리는데 리포트는
     * 사용자마다 질의를 날리므로, 겹치면 서로를 기다리게 한다.
     */
    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        LocalDate today = LocalDate.now(ZONE);
        if (!cacheService.setIfAbsent(today.toString(), CacheKind.PlayCountBatch, LOCK_TTL)) {
            log.info("재생수 집계 배치를 다른 인스턴스가 이미 잡았다. date={}", today);
            return;
        }
        refreshPlayCounts();
    }

    /**
     * 전 기간을 다시 세어 {@code music.play_count} 를 덮어쓴다. 갱신한 곡 수를 돌려준다.
     *
     * <h2>왜 하루치를 더하지 않고 전체를 다시 세는가</h2>
     *
     * <p>처음에는 하루치 증분을 더하는 쪽이 싸 보인다. 그러나 이 원천에는 증분을 쓸 수
     * 없는 성질이 셋 있다.
     *
     * <ul>
     *   <li><b>이벤트는 늦게 도착한다.</b> 수집기가 Kafka 로 보내고 ClickHouse 가 그것을
     *       소비한다. 하루치 경계로 자르면 그 경계에 걸친 이벤트가 어느 쪽에도 안 들어가거나
     *       두 번 들어간다. 늦게 도착한 것을 이미 지나간 날짜에 더하려면 <b>어느 날짜에
     *       더할지부터</b> 정할 수 없다.</li>
     *   <li><b>중복 제거가 나중에 일어난다.</b> {@code listen_events_local} 이
     *       {@code ReplicatedReplacingMergeTree} 라 같은 {@code event_id} 가 병합 전까지
     *       여러 행으로 남는다. 쿼리가 {@code GROUP BY event_id} 로 눌러 주지만, 눌러지기
     *       전에 센 값을 <b>더해 버리면</b> 그 중복이 컬럼에 남는다. 전체 재계산은 매번
     *       지금 눌러진 값을 적으므로 <b>스스로 낫는다.</b></li>
     *   <li><b>원천에 TTL 이 있다.</b> {@code listen_events} 는 18개월 뒤 파티션이 사라진다.
     *       증분으로 쌓은 컬럼은 그때 줄어들지 않아, 원천에 없는 재생수가 남아 기준선이
     *       조용히 부푼다.</li>
     * </ul>
     *
     * <p>비용은 문제가 되지 않는다. 실측(2026-09-20, 개발 스택)으로 전 기간 집계가
     * <b>0.65초</b>였다. 하루 한 번 도는 배치라면 증분을 만들어 유지할 값이 아니다.
     * 게다가 증분 방식은 "언제부터 언제까지를 더했는가" 를 어딘가에 남겨야 하는데, 그
     * 상태를 잃으면 값이 조용히 어긋난다 — <b>정확성을 상태 관리에 걸지 않는다.</b>
     *
     * <p><b>대입이지 덧셈이 아니다.</b> 두 번 돌려도, 하루를 건너뛰어도, 이벤트가 늦게
     * 도착해도 값이 원천으로 되돌아온다. 근거는 {@code MusicService.updatePlayCount} 와
     * {@code MusicRepository.updatePlayCount} 주석에 있다.
     *
     * @return 갱신한 곡 수. 집계 자체가 실패하면 {@code 0} 이고 컬럼은 하나도 안 바뀐다
     */
    public int refreshPlayCounts() {
        List<TrackPlayCount> counts;
        try {
            counts = analytics.catalogPlayCounts();
        } catch (DataAccessException e) {
            /*
             * 일부만 반영하지 않는다. 집계가 통째로 오지 않았는데 앞의 몇 곡만 덮어쓰면
             * 컬럼이 원천과 어긋난 채 하루를 넘기고, 그 사이 기본 정렬이 '절반만 맞는'
             * 순서로 나간다. 다음 날 배치가 다시 맞춘다.
             */
            log.warn("재생수 집계를 읽지 못해 이번 회차를 건너뛴다. 컬럼은 그대로 둔다.", e);
            return 0;
        }

        if (counts.isEmpty()) {
            // 재생 기록이 한 건도 없는 환경. 곡 수만큼 0 을 쓰는 것은 낭비라 하지 않는다 —
            // 컬럼의 기본값이 이미 0 이다.
            log.info("재생 기록이 없어 갱신할 곡이 없다.");
            return 0;
        }

        int updated = 0;
        int missingFromCatalog = 0;
        int failed = 0;
        for (TrackPlayCount row : counts) {
            try {
                if (musicService.updatePlayCount(row.trackId(), row.plays())) {
                    updated++;
                } else {
                    // 카탈로그에 없는 곡 번호. 에러가 아니다(삭제된 곡).
                    missingFromCatalog++;
                }
            } catch (RuntimeException e) {
                // 한 곡 때문에 나머지를 버리지 않는다.
                failed++;
                log.warn("재생수 갱신 실패 music_id={} plays={}", row.trackId(), row.plays(), e);
            }
        }

        log.info("재생수 집계 완료. 집계곡={} 갱신={} 카탈로그에없음={} 실패={}",
            counts.size(), updated, missingFromCatalog, failed);
        return updated;
    }

}
