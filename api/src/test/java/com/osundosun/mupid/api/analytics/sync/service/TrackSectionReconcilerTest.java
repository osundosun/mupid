package com.osundosun.mupid.api.analytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.osundosun.mupid.api.analytics.repository.TrackSectionRepository;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.SectionDigest;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.repository.SectionDigestRepository;
import com.osundosun.mupid.api.cache.service.CacheService;

/**
 * 대조의 판단 (S15P21A204-451, 설계 §8.2-4). 비교는 DB 를 보지 않는 함수라 요약을 직접 만들어
 * 넣는다. 레플리카 두 벌을 따로 비교한다는 점이 핵심이다 — 한쪽만 어긋나도 곡을 넣는다.
 */
class TrackSectionReconcilerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 4, 40);
    private static final TrackVersion A = new TrackVersion(1, "v1");
    private static final TrackVersion B = new TrackVersion(2, "v1");
    private static final TrackVersion C = new TrackVersion(3, "v1");

    private static SectionDigest digest(long sections, long checksum) {
        return new SectionDigest(sections, checksum);
    }

    private static Map<String, Map<TrackVersion, SectionDigest>> replicas(
        Map<TrackVersion, SectionDigest> first, Map<TrackVersion, SectionDigest> second) {
        Map<String, Map<TrackVersion, SectionDigest>> out = new LinkedHashMap<>();
        out.put("clickhouse-0", first);
        out.put("clickhouse-1", second);
        return out;
    }

    @Test
    @DisplayName("양쪽이 같으면 불일치가 없다")
    void noMismatchWhenEqual() {
        Map<TrackVersion, SectionDigest> mysql = Map.of(A, digest(9, 100), B, digest(3, 7));

        ReconcileReport report = TrackSectionReconciler.compare(mysql,
            replicas(Map.of(A, digest(9, 100), B, digest(3, 7)),
                     Map.of(A, digest(9, 100), B, digest(3, 7))), NOW);

        assertThat(report.checkedTracks()).isEqualTo(2);
        assertThat(report.mismatchedTracks()).isEmpty();
        assertThat(report.mismatchByReplica()).containsEntry("clickhouse-0", 0).containsEntry("clickhouse-1", 0);
    }

    @Test
    @DisplayName("레플리카 한쪽만 어긋나도 그 곡을 잡는다 — 값·수·빠짐 모두")
    void catchesMismatchOnOneReplica() {
        Map<TrackVersion, SectionDigest> mysql = Map.of(A, digest(9, 100), B, digest(3, 7), C, digest(2, 5));

        ReconcileReport report = TrackSectionReconciler.compare(mysql, replicas(
            Map.of(A, digest(9, 100), B, digest(3, 7), C, digest(2, 5)),
            // B 는 값이 다르고(라벨·경계가 바뀐 채 안 간 경우), C 는 이 레플리카에 없다.
            Map.of(A, digest(9, 100), B, digest(3, 8))), NOW);

        assertThat(report.mismatchedTracks()).containsExactly(B, C);
        assertThat(report.mismatchByReplica()).containsEntry("clickhouse-0", 0).containsEntry("clickhouse-1", 2);
    }

    @Test
    @DisplayName("구간 수가 다르면 체크섬이 같아도 불일치다")
    void countMismatch() {
        ReconcileReport report = TrackSectionReconciler.compare(Map.of(A, digest(9, 100)),
            replicas(Map.of(A, digest(8, 100)), Map.of(A, digest(9, 100))), NOW);

        assertThat(report.mismatchedTracks()).containsExactly(A);
    }

    @Test
    @DisplayName("통째로 빈 레플리카도 전 곡 불일치로 센다")
    void emptyReplicaIsMismatch() {
        ReconcileReport report = TrackSectionReconciler.compare(Map.of(A, digest(9, 100), B, digest(3, 7)),
            replicas(Map.of(A, digest(9, 100), B, digest(3, 7)), Map.of()), NOW);

        assertThat(report.mismatchedTracks()).containsExactly(A, B);
    }

    @Test
    @DisplayName("레플리카를 하나도 못 받으면 빈 결과를 일치로 읽지 않는다")
    void noReplicasMeansEverythingMismatched() {
        ReconcileReport report = TrackSectionReconciler.compare(Map.of(A, digest(9, 100)), Map.of(), NOW);

        assertThat(report.mismatchedTracks()).containsExactly(A);
    }

    @Test
    @DisplayName("ClickHouse 에만 있는 곡과 옛 버전 잔여는 세기만 하고 미반영에 넣지 않는다")
    void clickHouseOnlyAndStaleVersionsAreCountedNotEnqueued() {
        TrackVersion oldVersionOfA = new TrackVersion(1, "v0");
        TrackVersion deletedTrack = new TrackVersion(99, "v1");
        Map<TrackVersion, SectionDigest> both = Map.of(
            A, digest(9, 100), oldVersionOfA, digest(4, 1), deletedTrack, digest(2, 2));

        ReconcileReport report = TrackSectionReconciler.compare(Map.of(A, digest(9, 100)),
            replicas(both, both), NOW);

        assertThat(report.mismatchedTracks()).isEmpty();
        assertThat(report.staleVersions()).isEqualTo(1);
        assertThat(report.onlyInClickHouse()).isEqualTo(1);
    }

    @Test
    @DisplayName("대조가 찾은 곡을 MySQL 의 버전으로 미반영에 넣고 결과를 남긴다")
    void reconcileEnqueuesMismatches() {
        SectionDigestRepository mysql = mock(SectionDigestRepository.class);
        TrackSectionRepository clickHouse = mock(TrackSectionRepository.class);
        TrackSectionSync sync = mock(TrackSectionSync.class);
        AnalyticsSyncMonitor monitor = mock(AnalyticsSyncMonitor.class);
        when(mysql.digests()).thenReturn(Map.of(A, digest(9, 100), B, digest(3, 7)));
        when(clickHouse.digestsByReplica()).thenReturn(
            replicas(Map.of(A, digest(9, 100)), Map.of(A, digest(9, 100), B, digest(3, 7))));

        ReconcileReport report = new TrackSectionReconciler(mysql, clickHouse, sync, monitor,
            mock(CacheService.class)).reconcile();

        assertThat(report.mismatchCount()).isEqualTo(1);
        verify(sync).enqueue(2, "v1");
        verify(sync, never()).enqueue(1, "v1");
        verify(monitor).recordReconcile(report);
    }

    @Test
    @DisplayName("ClickHouse 가 안 닿으면 아무것도 넣지 않고 실패를 남긴다")
    void failedReadEnqueuesNothing() {
        SectionDigestRepository mysql = mock(SectionDigestRepository.class);
        TrackSectionRepository clickHouse = mock(TrackSectionRepository.class);
        TrackSectionSync sync = mock(TrackSectionSync.class);
        AnalyticsSyncMonitor monitor = mock(AnalyticsSyncMonitor.class);
        when(mysql.digests()).thenReturn(Map.of(A, digest(9, 100)));
        when(clickHouse.digestsByReplica()).thenThrow(new DataAccessResourceFailureException("replica down"));

        ReconcileReport report = new TrackSectionReconciler(mysql, clickHouse, sync, monitor,
            mock(CacheService.class)).reconcile();

        assertThat(report.succeeded()).isFalse();
        assertThat(report.error()).contains("replica down");
        verify(sync, never()).enqueue(anyInt(), anyString());
        verify(monitor).recordReconcile(any());
    }

}
