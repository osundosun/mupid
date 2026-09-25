package com.osundosun.mupid.api.analytics.sync.service;

import java.util.List;
import java.util.function.IntFunction;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync.RetryOutcome;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 미반영 곡을 1분마다 ClickHouse 에 다시 쓴다 (S15P21A204-451, 설계 §8.2-3).
 *
 * <h2>인스턴스마다 돈다 — 선점 대신 행 잠금</h2>
 *
 * <p>스케줄러는 API 인스턴스마다 돈다({@code ApiApplication}). 다른 배치들은 Redis 로 한
 * 인스턴스만 돌게 하지만 여기는 그러지 않는다. 곡 하나를 가져가는 것이
 * {@code FOR UPDATE SKIP LOCKED} 라, 여러 벌이 동시에 돌아도 한 곡을 두 번 쓰지 않고 서로
 * 잡지 않은 곡을 나눠 가진다. 설령 같은 곡을 두 번 써도 틀리지 않는다 — MySQL 의 지금
 * 구간으로 <b>교체</b>하는 것이라 멱등이다.
 *
 * <h2>한 곡이 실패하면 이번 회차를 멈춘다</h2>
 *
 * <p>실패는 대개 ClickHouse 가 안 닿는 것이고, 그러면 나머지도 전부 실패한다. 곡마다
 * 연결 시간 제한(3초)을 기다리면 200곡에 10분이 걸려 다음 회차와 겹친다. 실패한 곡은
 * {@code updated_at} 이 올라 다음 회차에 뒤로 가므로, 고칠 수 없는 곡 하나가 매번 앞을
 * 막지도 않는다.
 *
 * <h2>곡 특징도 같은 회차에서 (S15P21A204-455)</h2>
 *
 * <p>{@code TRACK_FEATURES} 종류({@link TrackFeatureSync})를 구간 다음에 같은 규칙으로 돈다. 종류마다
 * 따로 멈춘다 — 구간 쪽 한 곡이 실패해도 곡 삭제 표시는 간다. 이름은 구간 시절의 것을 그대로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackSectionRetryJob {

    /** 한 회차에 잡는 곡 수. 한 곡은 SELECT·INSERT 한 번씩이라 이 정도면 1분 안에 끝난다. */
    static final int BATCH = 200;

    private final AnalyticsSyncPendingRepository pendingRepository;
    private final TrackSectionSync sync;
    private final TrackFeatureSync featureSync;
    private final AnalyticsSyncMonitor monitor;

    /** 한 회차 결과. */
    public record Round(int synced, int skipped, int nothingToWrite, int failed) {

        int touched() {
            return synced + nothingToWrite + failed;
        }

    }

    /**
     * 1분 간격. 앞 회차가 끝난 뒤부터 센다(fixedDelay) — 회차가 길어져도 겹치지 않는다.
     * 기동 직후에는 1분 쉰다. 컨텍스트가 뜨자마자 DB 를 때리지 않게 한다.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void run() {
        try {
            Round round = retryPending();
            if (round.touched() > 0) {
                log.info("ClickHouse 미반영 재시도 — 반영 {} · 구간없음 {} · 실패 {} · 다른 인스턴스 {}",
                    round.synced(), round.nothingToWrite(), round.failed(), round.skipped());
            }
        } catch (RuntimeException e) {
            // MySQL 이 안 닿는 경우. 다음 회차가 다시 본다.
            log.warn("ClickHouse 미반영 재시도를 하지 못했다.", e);
        }
        try {
            Round round = retryFeatures();
            if (round.touched() > 0) {
                log.info("ClickHouse 곡 특징 미반영 재시도 — 반영 {} · 곡없음 {} · 실패 {} · 다른 인스턴스 {}",
                    round.synced(), round.nothingToWrite(), round.failed(), round.skipped());
            }
        } catch (RuntimeException e) {
            log.warn("ClickHouse 곡 특징 미반영 재시도를 하지 못했다.", e);
        }
        monitor.refreshPending();
    }

    /** 구간({@code CLICKHOUSE}) 한 회차. */
    public Round retryPending() {
        return retry(TrackSectionSync.KIND, sync::retry);
    }

    /** 곡 특징({@code TRACK_FEATURES}) 한 회차. */
    public Round retryFeatures() {
        return retry(TrackFeatureSync.KIND, featureSync::retry);
    }

    private Round retry(AnalyticsSyncKind kind, IntFunction<RetryOutcome> retrier) {
        List<Integer> candidates = pendingRepository.findCandidates(kind.name(), BATCH);
        int synced = 0;
        int skipped = 0;
        int nothingToWrite = 0;
        int failed = 0;
        for (Integer musicId : candidates) {
            RetryOutcome outcome;
            try {
                outcome = retrier.apply(musicId);
            } catch (RuntimeException e) {
                // MySQL 쪽 예외(잠금 대기 초과 등). 실패로 세고 멈춘다.
                log.warn("미반영 곡 재시도 중 MySQL 오류 musicId={}", musicId, e);
                outcome = RetryOutcome.FAILED;
            }
            switch (outcome) {
                case SYNCED -> synced++;
                case SKIPPED -> skipped++;
                case NOTHING_TO_WRITE -> nothingToWrite++;
                case FAILED -> failed++;
            }
            if (outcome == RetryOutcome.FAILED) {
                break;
            }
        }
        return new Round(synced, skipped, nothingToWrite, failed);
    }

}
