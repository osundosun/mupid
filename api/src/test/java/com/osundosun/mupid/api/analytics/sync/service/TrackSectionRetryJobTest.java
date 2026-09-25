package com.osundosun.mupid.api.analytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync.RetryOutcome;

/**
 * 재시도 회차의 규칙 (S15P21A204-451). 곡 하나를 어떻게 다시 쓰는지는 {@link TrackSectionSyncTest}.
 */
class TrackSectionRetryJobTest {

    private final AnalyticsSyncPendingRepository pending = mock(AnalyticsSyncPendingRepository.class);
    private final TrackSectionSync sync = mock(TrackSectionSync.class);
    private final AnalyticsSyncMonitor monitor = mock(AnalyticsSyncMonitor.class);
    private final TrackSectionRetryJob job = new TrackSectionRetryJob(pending, sync, monitor);

    @Test
    @DisplayName("남은 곡을 차례로 다시 쓰고, 다른 인스턴스가 잡은 곡은 건너뛴다")
    void retriesEveryCandidate() {
        when(pending.findCandidates("CLICKHOUSE", TrackSectionRetryJob.BATCH)).thenReturn(List.of(1, 2, 3));
        when(sync.retry(1)).thenReturn(RetryOutcome.SYNCED);
        when(sync.retry(2)).thenReturn(RetryOutcome.SKIPPED);
        when(sync.retry(3)).thenReturn(RetryOutcome.SYNCED);

        TrackSectionRetryJob.Round round = job.retryPending();

        assertThat(round.synced()).isEqualTo(2);
        assertThat(round.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("한 곡이 실패하면 회차를 멈춘다 — ClickHouse 가 죽었으면 나머지도 실패한다")
    void stopsAtFirstFailure() {
        when(pending.findCandidates("CLICKHOUSE", TrackSectionRetryJob.BATCH)).thenReturn(List.of(1, 2, 3));
        when(sync.retry(1)).thenReturn(RetryOutcome.SYNCED);
        when(sync.retry(2)).thenReturn(RetryOutcome.FAILED);

        TrackSectionRetryJob.Round round = job.retryPending();

        assertThat(round.synced()).isEqualTo(1);
        assertThat(round.failed()).isEqualTo(1);
        verify(sync, never()).retry(3);
    }

    @Test
    @DisplayName("MySQL 예외도 실패로 세고 멈춘다")
    void mysqlErrorCountsAsFailure() {
        when(pending.findCandidates("CLICKHOUSE", TrackSectionRetryJob.BATCH)).thenReturn(List.of(1, 2));
        when(sync.retry(1)).thenThrow(new CannotAcquireLockException("lock wait timeout"));

        TrackSectionRetryJob.Round round = job.retryPending();

        assertThat(round.failed()).isEqualTo(1);
        verify(sync, never()).retry(2);
    }

    @Test
    @DisplayName("회차가 통째로 실패해도 지표는 갱신한다")
    void refreshesMetricsEvenWhenRoundFails() {
        when(pending.findCandidates("CLICKHOUSE", TrackSectionRetryJob.BATCH))
            .thenThrow(new CannotAcquireLockException("mysql gone"));

        job.run();

        verify(sync, never()).retry(anyInt());
        verify(monitor).refreshPending();
    }

}
