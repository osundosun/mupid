package com.osundosun.mupid.api.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.service.AnalyticsSyncMonitor;
import com.osundosun.mupid.api.analytics.sync.service.VectorSync;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.vector.client.VectorPointStore.Point;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository.Fingerprint;
import com.osundosun.mupid.api.vector.schema.QdrantSchema;
import com.osundosun.mupid.api.vector.service.VectorReconciler;

/**
 * 벡터 대조 (S15P21A204-457, 설계 §8.2-4·§7.4) — Qdrant 는 메모리 판이다.
 */
class VectorReconcilerTest {

    private final QdrantSchema schema = QdrantSchema.fromResource();
    private InMemoryVectorPointStore store;
    private VectorSectionRepository mysql;
    private VectorSync sync;
    private AnalyticsSyncMonitor monitor;
    private VectorReconciler reconciler;

    @BeforeEach
    void setUp() {
        store = new InMemoryVectorPointStore();
        mysql = mock(VectorSectionRepository.class);
        sync = mock(VectorSync.class);
        monitor = mock(AnalyticsSyncMonitor.class);
        QdrantProperties props = new QdrantProperties("http://qdrant.test", "k", null,
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, Duration.ofSeconds(1));
        reconciler = new VectorReconciler(props, mysql, store, sync, monitor, mock(CacheService.class));
    }

    private void qdrantHas(int track, String externalId, String version, int start, int end) {
        UUID id = schema.pointId(externalId);
        store.points.put(id, new Point(id, Map.of("mert", new float[1536]), Map.of("track_id", track,
            "external_id", externalId, "analysis_version", version, "seg_start_ms", start, "seg_end_ms", end)));
    }

    private static Map<String, Fingerprint> track(String prefix, String version, int sections) {
        Map<String, Fingerprint> out = new LinkedHashMap<>();
        for (int i = 0; i < sections; i++) {
            out.put(prefix + "#" + i, new Fingerprint(version, i * 1000L, (i + 1) * 1000L));
        }
        return out;
    }

    @Test
    @DisplayName("없는 곡·모자란 곡·버전이 다른 곡은 미반영 VECTOR 로, 맞는 곡은 그대로, 삭제된 곡의 포인트는 지운다")
    void enqueuesMismatchesAndDeletesOrphans() {
        Map<Integer, Map<String, Fingerprint>> expected = new LinkedHashMap<>();
        expected.put(1, track("T1", "v1", 2));   // 맞다
        expected.put(2, track("T2", "v1", 2));   // Qdrant 에 없다
        expected.put(3, track("T3", "v2", 2));   // Qdrant 는 옛 버전
        expected.put(4, track("T4", "v1", 3));   // 하나 모자란다
        when(mysql.aliveFingerprints()).thenReturn(expected);
        for (int i = 0; i < 2; i++) {
            qdrantHas(1, "T1#" + i, "v1", i * 1000, (i + 1) * 1000);
            qdrantHas(3, "T3#" + i, "v1", i * 1000, (i + 1) * 1000);
            qdrantHas(4, "T4#" + i, "v1", i * 1000, (i + 1) * 1000);
        }
        qdrantHas(9, "T9#0", "v1", 0, 1000);     // 삭제된 곡 (MySQL 에 살아 있지 않다)

        ReconcileReport report = reconciler.reconcile();

        assertThat(report.checkedTracks()).isEqualTo(4);
        assertThat(report.mismatchCount()).isEqualTo(3);
        verify(sync).enqueue(2, "v1");
        verify(sync).enqueue(3, "v2");
        verify(sync).enqueue(4, "v1");
        verify(sync, never()).enqueue(eq(1), any());
        assertThat(report.onlyInClickHouse()).isEqualTo(1);
        assertThat(store.points).doesNotContainKey(schema.pointId("T9#0"));
        verify(monitor).recordReconcile(eq(AnalyticsSyncKind.VECTOR), any());
    }

    @Test
    @DisplayName("지울 곡이 Qdrant 곡의 절반을 넘거나 MySQL 이 비었으면 지우지 않는다")
    void refusesMassDelete() {
        when(mysql.aliveFingerprints()).thenReturn(Map.of());
        qdrantHas(9, "T9#0", "v1", 0, 1000);
        reconciler.reconcile();
        assertThat(store.points).containsKey(schema.pointId("T9#0"));
    }

    @Test
    @DisplayName("Qdrant 가 안 닿으면 아무것도 넣지 않고 실패로 남긴다")
    void failsWithoutEnqueue() {
        when(mysql.aliveFingerprints()).thenReturn(Map.of(1, track("T1", "v1", 1)));
        store.down = true;
        ReconcileReport report = reconciler.reconcile();
        assertThat(report.succeeded()).isFalse();
        verify(sync, never()).enqueue(any(Integer.class), any());
    }

}
