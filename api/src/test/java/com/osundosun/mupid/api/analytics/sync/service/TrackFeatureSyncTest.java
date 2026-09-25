package com.osundosun.mupid.api.analytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;
import com.osundosun.mupid.api.analytics.repository.TrackFeatureRepository;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.repository.TrackFeatureSource;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync.RetryOutcome;

/**
 * 곡 특징 사본의 미반영 기록·커밋 뒤 쓰기·재시도·대조 판단 (S15P21A204-455).
 *
 * <p>{@link TrackSectionSyncTest} 와 같은 방식이다 — DB·ClickHouse 없이, 트랜잭션 관리자는 가짜다.
 * 확인하려는 것은 어느 행을 지우고 남기는가, 그리고 대조가 어느 곡을 되돌리는가다.
 */
class TrackFeatureSyncTest {

    private static final int MUSIC_ID = 1001;
    private static final String KIND = "TRACK_FEATURES";

    private AnalyticsSyncPendingRepository pending;
    private TrackFeatureSource source;
    private TrackFeatureRepository clickHouse;
    private TrackFeatureSync sync;

    @BeforeEach
    void setUp() {
        pending = mock(AnalyticsSyncPendingRepository.class);
        source = mock(TrackFeatureSource.class);
        clickHouse = mock(TrackFeatureRepository.class);
        sync = new TrackFeatureSync(pending, source, clickHouse, mock(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    static TrackFeatureRow row(int musicId, boolean deleted) {
        return new TrackFeatureRow(musicId, "T00" + musicId, 180_000, 120.0, null, null, "ko", "pop",
            List.of("k_ballad"), List.of("serene"), true, "F", deleted, true, "v1");
    }

    @Test
    @DisplayName("트랜잭션 안에서 같은 곡이 여러 번 올라와도 커밋 뒤 쓰기는 한 번이다")
    void marksEveryTimeButPushesOncePerTransaction() {
        when(pending.findRevision(MUSIC_ID, KIND)).thenReturn(Optional.of(1L), Optional.of(2L));
        when(pending.findRevision(1002, KIND)).thenReturn(Optional.of(5L));
        TransactionSynchronizationManager.initSynchronization();

        sync.markChanged(MUSIC_ID);
        sync.markChanged(MUSIC_ID);
        sync.markChanged(1002);

        verify(pending, times(2)).upsert(MUSIC_ID, KIND, TrackFeatureSync.NO_VERSION);
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);

        when(source.load(Set.of(MUSIC_ID, 1002)))
            .thenReturn(Map.of(MUSIC_ID, row(MUSIC_ID, false), 1002, row(1002, true)));
        registered.get(0).afterCommit();
        registered.get(0).afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        verify(clickHouse, times(1)).replace(anyList());
        // 마지막으로 남긴 revision 으로 지운다.
        verify(pending).deleteIfRevision(MUSIC_ID, KIND, 2L);
        verify(pending).deleteIfRevision(1002, KIND, 5L);
    }

    @Test
    @DisplayName("커밋 뒤 쓰기가 실패하면 실패를 적고 행을 남긴다 — 예외를 던지지 않는다")
    void failedPushKeepsRows() {
        Map<Integer, Long> revisions = new LinkedHashMap<>(Map.of(MUSIC_ID, 3L));
        when(source.load(revisions.keySet())).thenReturn(Map.of(MUSIC_ID, row(MUSIC_ID, true)));
        doThrow(new DataAccessResourceFailureException("Connection refused"))
            .when(clickHouse).replace(anyList());

        assertThat(sync.pushAfterCommit(revisions)).isFalse();

        verify(pending, never()).deleteIfRevision(anyInt(), anyString(), anyLong());
        verify(pending).markFailed(eq(MUSIC_ID), eq(KIND), anyString());
    }

    @Test
    @DisplayName("재시도는 MySQL 의 지금 특징을 쓰고 행을 지운다 — 삭제된 곡도 is_deleted 로 간다")
    void retryWritesCurrentMysql() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.of(4L));
        when(source.load(MUSIC_ID)).thenReturn(row(MUSIC_ID, true));

        assertThat(sync.retry(MUSIC_ID)).isEqualTo(RetryOutcome.SYNCED);

        verify(clickHouse).replace(List.of(row(MUSIC_ID, true)));
        verify(pending).deletePending(MUSIC_ID, KIND);
    }

    @Test
    @DisplayName("다른 인스턴스가 잡은 곡은 건너뛰고, 실패하면 행을 남긴다")
    void retrySkipsAndFails() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.empty());
        assertThat(sync.retry(MUSIC_ID)).isEqualTo(RetryOutcome.SKIPPED);

        when(pending.claim(1002, KIND)).thenReturn(Optional.of(1L));
        when(source.load(1002)).thenReturn(row(1002, false));
        doThrow(new DataAccessResourceFailureException("down")).when(clickHouse).replace(anyList());
        assertThat(sync.retry(1002)).isEqualTo(RetryOutcome.FAILED);
        verify(pending).markFailed(eq(1002), eq(KIND), anyString());
        verify(pending, never()).deletePending(1002, KIND);
    }

    @Test
    @DisplayName("대조: 없거나 값이 다른 곡은 레플리카마다 세고, 빈 레플리카는 전 곡 불일치다")
    void reconcileCompare() {
        Map<Integer, Long> mysql = Map.of(1, 11L, 2, 22L, 3, 33L);
        Map<String, Map<Integer, Long>> replicas = new LinkedHashMap<>();
        replicas.put("ch1", Map.of(1, 11L, 2, 99L, 3, 33L, 7, 70L));
        replicas.put("ch2", Map.of());

        ReconcileReport report = TrackFeatureReconciler.compare(mysql, replicas, LocalDateTime.now());

        assertThat(report.mismatchedTracks()).extracting(TrackVersion::musicId).containsExactly(1, 2, 3);
        assertThat(report.mismatchByReplica()).containsEntry("ch1", 1).containsEntry("ch2", 3);
        assertThat(report.onlyInClickHouse()).isEqualTo(1);
        assertThat(report.checkedTracks()).isEqualTo(3);
    }

    @Test
    @DisplayName("대조가 어긋난 곡을 미반영에 넣고 지표를 남긴다. 못 읽으면 아무것도 넣지 않는다")
    void reconcileEnqueues() {
        AnalyticsSyncMonitor monitor = mock(AnalyticsSyncMonitor.class);
        TrackFeatureSync featureSync = mock(TrackFeatureSync.class);
        TrackFeatureReconciler reconciler = new TrackFeatureReconciler(source, clickHouse, featureSync,
            monitor, mock(com.osundosun.mupid.api.cache.service.CacheService.class));
        when(source.loadAll()).thenReturn(Map.of(1, row(1, false), 2, row(2, true)));
        when(clickHouse.rowsByReplica()).thenReturn(Map.of(
            "ch1", Map.of(1, row(1, false), 2, row(2, false)),
            "ch2", Map.of(1, row(1, false), 2, row(2, true))));

        ReconcileReport report = reconciler.reconcile();

        // 곡 2 는 MySQL 에서 지워졌는데 ch1 은 아직 모른다.
        assertThat(report.mismatchedTracks()).extracting(TrackVersion::musicId).containsExactly(2);
        verify(featureSync).enqueue(2);
        verify(monitor).recordReconcile(com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind.TRACK_FEATURES,
            report);

        when(clickHouse.rowsByReplica()).thenThrow(new DataAccessResourceFailureException("down"));
        assertThat(reconciler.reconcile().succeeded()).isFalse();
        verify(featureSync, times(1)).enqueue(anyInt());
    }

    @Test
    @DisplayName("체크섬은 값 하나만 달라도 바뀌고, null 과 빈 문자열을 가른다")
    void digestSeesEveryField() {
        TrackFeatureRow base = row(1, false);
        assertThat(base.digest()).isEqualTo(row(1, false).digest());
        assertThat(row(1, true).digest()).isNotEqualTo(base.digest());
        TrackFeatureRow blankLanguage = new TrackFeatureRow(1, "T001", 180_000, 120.0, null, null, "", "pop",
            List.of("k_ballad"), List.of("serene"), true, "F", false, true, "v1");
        TrackFeatureRow nullLanguage = new TrackFeatureRow(1, "T001", 180_000, 120.0, null, null, null, "pop",
            List.of("k_ballad"), List.of("serene"), true, "F", false, true, "v1");
        assertThat(blankLanguage.digest()).isNotEqualTo(nullLanguage.digest());
    }

}
