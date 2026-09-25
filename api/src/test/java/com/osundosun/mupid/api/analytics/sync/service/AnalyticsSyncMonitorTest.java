package com.osundosun.mupid.api.analytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.osundosun.mupid.api.admin.response.AdminDependencyStatus;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.AnalyticsSyncMonitor.LastReconcile;
import com.osundosun.mupid.api.cache.service.CacheService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 동기화 지표와 알림 기준 (S15P21A204-451, 설계 §8.3 — 미반영 10분, 불일치 1건).
 */
class AnalyticsSyncMonitorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 9, 0);
    private static final LastReconcile CLEAN =
        new LastReconcile(NOW.minusHours(4), 1060, 0, "clickhouse-0 0, clickhouse-1 0", null);

    @Test
    @DisplayName("미반영이 없고 마지막 대조가 깨끗하면 UP")
    void upWhenClean() {
        AdminDependencyStatus status = AnalyticsSyncMonitor.evaluate(0, 0, Optional.of(CLEAN), NOW, 3);

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.detail()).isNull();
        assertThat(status.target()).contains("미반영 0곡", "검사 1060곡", "불일치 0곡");
    }

    @Test
    @DisplayName("미반영이 있어도 10분 안이면 UP — 커밋 직후 잠깐 남는 것은 정상이다")
    void youngPendingIsFine() {
        AdminDependencyStatus status = AnalyticsSyncMonitor.evaluate(3, 9 * 60, Optional.of(CLEAN), NOW, 3);

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.target()).contains("미반영 3곡", "가장 오래된 9분");
    }

    @Test
    @DisplayName("가장 오래된 미반영이 10분을 넘으면 WARN")
    void warnsOnOldPending() {
        AdminDependencyStatus status = AnalyticsSyncMonitor.evaluate(3, 10 * 60, Optional.of(CLEAN), NOW, 3);

        assertThat(status.status()).isEqualTo("WARN");
        assertThat(status.detail()).contains("10분");
    }

    @Test
    @DisplayName("마지막 대조에 불일치가 1곡이라도 있으면 WARN")
    void warnsOnMismatch() {
        LastReconcile mismatched = new LastReconcile(NOW.minusHours(4), 1060, 1, "clickhouse-0 0, clickhouse-1 1", null);

        AdminDependencyStatus status = AnalyticsSyncMonitor.evaluate(0, 0, Optional.of(mismatched), NOW, 3);

        assertThat(status.status()).isEqualTo("WARN");
        assertThat(status.detail()).contains("1곡");
        assertThat(status.target()).contains("clickhouse-1 1");
    }

    @Test
    @DisplayName("대조가 실패했거나 하루 넘게 안 돌았으면 WARN")
    void warnsOnFailedOrStaleReconcile() {
        LastReconcile failed = new LastReconcile(NOW.minusHours(4), 0, 0, "", "ConnectException: refused");
        LastReconcile stale = new LastReconcile(NOW.minusHours(27), 1060, 0, "", null);

        assertThat(AnalyticsSyncMonitor.evaluate(0, 0, Optional.of(failed), NOW, 3).detail()).contains("실패");
        assertThat(AnalyticsSyncMonitor.evaluate(0, 0, Optional.of(stale), NOW, 3).detail()).contains("26시간");
    }

    @Test
    @DisplayName("대조 기록이 아직 없으면 알리지 않고 그렇다고만 적는다 — 배포 첫날")
    void noReconcileYet() {
        AdminDependencyStatus status = AnalyticsSyncMonitor.evaluate(0, 0, Optional.empty(), NOW, 3);

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.target()).contains("대조 기록 없음");
    }

    @Test
    @DisplayName("Redis 에 남긴 결과를 되읽는다 — 사유에 | 가 있어도")
    void encodeDecodeRoundTrip() {
        Map<String, Integer> byReplica = new LinkedHashMap<>();
        byReplica.put("clickhouse-0", 0);
        byReplica.put("clickhouse-1", 2);
        ReconcileReport report = new ReconcileReport(NOW, 1060,
            Set.of(new TrackVersion(1, "v1"), new TrackVersion(2, "v1")), byReplica, 0, 0, null);

        LastReconcile decoded = AnalyticsSyncMonitor.decode(AnalyticsSyncMonitor.encode(report)).orElseThrow();

        assertThat(decoded).isEqualTo(new LastReconcile(NOW, 1060, 2, "clickhouse-0 0, clickhouse-1 2", null));

        ReconcileReport failed = ReconcileReport.failed(NOW, "Exception: a|b");
        assertThat(AnalyticsSyncMonitor.decode(AnalyticsSyncMonitor.encode(failed)).orElseThrow().error())
            .isEqualTo("Exception: a|b");
        assertThat(AnalyticsSyncMonitor.decode("garbage")).isEmpty();
    }

    @Test
    @DisplayName("게이지는 재시도 작업이 채운 값을 준다 — 대조 전에는 불일치가 -1")
    void gaugesReflectLastRefresh() {
        AnalyticsSyncPendingRepository pending = mock(AnalyticsSyncPendingRepository.class);
        when(pending.countPending("CLICKHOUSE")).thenReturn(4L);
        when(pending.oldestAgeSeconds("CLICKHOUSE")).thenReturn(125L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("meterRegistry", registry);

        AnalyticsSyncMonitor monitor = new AnalyticsSyncMonitor(pending, mock(CacheService.class),
            beans.getBeanProvider(MeterRegistry.class));
        assertThat(registry.get("mupid.analytics.reconcile.mismatch").gauge().value()).isEqualTo(-1);

        monitor.refreshPending();
        monitor.recordReconcile(new ReconcileReport(NOW, 10, Set.of(new TrackVersion(1, "v1")), Map.of(), 0, 0, null));

        assertThat(registry.get("mupid.analytics.sync.pending").tag("kind", "CLICKHOUSE").gauge().value())
            .isEqualTo(4);
        assertThat(registry.get("mupid.analytics.sync.pending.oldest.age").tag("kind", "CLICKHOUSE")
            .gauge().value()).isEqualTo(125);
        assertThat(registry.get("mupid.analytics.reconcile.mismatch").tag("kind", "CLICKHOUSE")
            .gauge().value()).isEqualTo(1);
        // 곡 특징은 따로 센다 — 구간 대조가 곡 특징 게이지를 건드리지 않는다.
        assertThat(registry.get("mupid.analytics.reconcile.mismatch").tag("kind", "TRACK_FEATURES")
            .gauge().value()).isEqualTo(-1);
    }

    @Test
    @DisplayName("대시보드에는 종류마다 한 줄 — 구간·곡 특징")
    void oneDashboardLinePerKind() {
        AnalyticsSyncPendingRepository pending = mock(AnalyticsSyncPendingRepository.class);
        when(pending.countPending("TRACK_FEATURES")).thenReturn(2L);
        when(pending.oldestAgeSeconds("TRACK_FEATURES")).thenReturn(11 * 60L);
        AnalyticsSyncMonitor monitor = new AnalyticsSyncMonitor(pending, mock(CacheService.class),
            new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));

        assertThat(monitor.statuses()).extracting(AdminDependencyStatus::name)
            .containsExactly("ClickHouse 구간 동기화", "ClickHouse 곡 특징 동기화");
        assertThat(monitor.status(AnalyticsSyncKind.TRACK_FEATURES).status()).isEqualTo("WARN");
        assertThat(monitor.status(AnalyticsSyncKind.CLICKHOUSE).status()).isEqualTo("UP");
    }

}
