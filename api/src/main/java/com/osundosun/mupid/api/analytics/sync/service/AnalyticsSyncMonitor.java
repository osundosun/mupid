package com.osundosun.mupid.api.analytics.sync.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.admin.response.AdminDependencyStatus;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 사본 동기화의 지표와 알림 기준 (S15P21A204-451, 설계 §8.3).
 *
 * <p>세 값을 본다 — 미반영 건수, 가장 오래된 미반영의 나이, 마지막 대조의 불일치 수.
 * 두 곳에 내보낸다.
 *
 * <ul>
 *   <li><b>관리자 대시보드의 연결 상태</b>({@link #status()}) — 사람이 실제로 보는 곳이다.
 *       화면을 열 때 MySQL 에서 바로 센다. 기준을 넘으면 {@code WARN} 으로 칠한다.</li>
 *   <li><b>Micrometer 게이지</b> — {@code mupid.analytics.sync.pending}·
 *       {@code …pending.oldest.age}(초)·{@code mupid.analytics.reconcile.mismatch}. 지금은
 *       액추에이터가 {@code health} 만 내보내 밖에서 긁을 수 없지만, 수집기를 붙이는 날 코드가
 *       바뀌지 않게 이름을 먼저 정해 둔다. 긁을 때 DB 를 치지 않도록 재시도 작업이 1분마다
 *       채운 값을 준다. 인스턴스마다 자기가 본 값이다.</li>
 * </ul>
 */
@Slf4j
@Component
public class AnalyticsSyncMonitor {

    /** 가장 오래된 미반영이 이보다 늙으면 알린다. 재시도가 1분 간격이라 열 번을 놓친 셈이다. */
    public static final Duration PENDING_AGE_ALERT = Duration.ofMinutes(10);

    /** 마지막 대조의 불일치가 이 이상이면 알린다. 정상은 0 이다. */
    public static final int MISMATCH_ALERT = 1;

    /**
     * 마지막 대조가 이보다 오래됐으면 알린다. 매일 도는 작업이라 하루하고 조금 — 한 번 거른
     * 것을 알아채는 기준이다.
     */
    public static final Duration RECONCILE_STALE_ALERT = Duration.ofHours(26);

    static final String DEPENDENCY_NAME = "ClickHouse 구간 동기화";

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String LAST_KEY = "track-sections";

    /** 대조 결과를 들고 있는 시간. 며칠 안 돌아도 "마지막이 언제였나" 는 보여야 한다. */
    private static final Duration LAST_TTL = Duration.ofDays(14);

    private final AnalyticsSyncPendingRepository pendingRepository;
    private final CacheService cacheService;

    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong oldestAgeGauge = new AtomicLong();
    /** 아직 대조한 적이 없으면 -1. 0 과 구분해야 "일치" 로 오해하지 않는다. */
    private final AtomicLong mismatchGauge = new AtomicLong(-1);

    public AnalyticsSyncMonitor(AnalyticsSyncPendingRepository pendingRepository,
                                CacheService cacheService,
                                ObjectProvider<MeterRegistry> meterRegistry) {
        this.pendingRepository = pendingRepository;
        this.cacheService = cacheService;
        meterRegistry.ifAvailable(this::registerGauges);
    }

    private void registerGauges(MeterRegistry registry) {
        String kind = TrackSectionSync.KIND.name();
        Gauge.builder("mupid.analytics.sync.pending", pendingGauge, AtomicLong::get)
            .description("사본에 아직 반영되지 않은 곡 수")
            .tag("kind", kind)
            .register(registry);
        Gauge.builder("mupid.analytics.sync.pending.oldest.age", oldestAgeGauge, AtomicLong::get)
            .description("가장 오래된 미반영의 나이")
            .baseUnit("seconds")
            .tag("kind", kind)
            .register(registry);
        Gauge.builder("mupid.analytics.reconcile.mismatch", mismatchGauge, AtomicLong::get)
            .description("마지막 대조에서 어긋난 곡 수 (-1: 아직 대조 전)")
            .tag("kind", kind)
            .register(registry);
    }

    /** 재시도 작업이 회차마다 부른다. */
    public void refreshPending() {
        try {
            pendingGauge.set(pendingRepository.countPending(TrackSectionSync.KIND.name()));
            Long age = pendingRepository.oldestAgeSeconds(TrackSectionSync.KIND.name());
            oldestAgeGauge.set(age == null ? 0 : age);
        } catch (RuntimeException e) {
            log.warn("미반영 지표를 갱신하지 못했다: {}", e.toString());
        }
    }

    /** 대조 작업이 끝날 때 부른다. 실패한 대조도 남긴다. */
    public void recordReconcile(ReconcileReport report) {
        if (report.succeeded()) {
            mismatchGauge.set(report.mismatchCount());
        }
        try {
            cacheService.set(LAST_KEY, encode(report), CacheKind.AnalyticsReconcileLast, LAST_TTL);
        } catch (RuntimeException e) {
            log.warn("대조 결과를 Redis 에 남기지 못했다: {}", e.toString());
        }
    }

    /**
     * 관리자 대시보드 연결 상태 한 줄. 기준을 넘으면 {@code WARN}, MySQL 이 안 닿으면 {@code DOWN}.
     */
    public AdminDependencyStatus status() {
        long started = System.nanoTime();
        long pending;
        long oldestAgeSeconds;
        try {
            pending = pendingRepository.countPending(TrackSectionSync.KIND.name());
            Long age = pendingRepository.oldestAgeSeconds(TrackSectionSync.KIND.name());
            oldestAgeSeconds = age == null ? 0 : age;
        } catch (RuntimeException e) {
            return new AdminDependencyStatus(DEPENDENCY_NAME, "DOWN", "-", -1,
                "미반영 기록을 읽지 못했습니다. " + TrackSectionSync.describe(e));
        }
        Optional<LastReconcile> last = readLast();
        long latency = (System.nanoTime() - started) / 1_000_000;
        return evaluate(pending, oldestAgeSeconds, last, LocalDateTime.now(ZONE), latency);
    }

    /** 기준 판정. DB 를 보지 않는다 — 시험하려고 떼어 둔다. */
    static AdminDependencyStatus evaluate(long pending, long oldestAgeSeconds,
                                          Optional<LastReconcile> last, LocalDateTime now,
                                          long latency) {
        List<String> alerts = new ArrayList<>();
        StringBuilder target = new StringBuilder("미반영 %d곡".formatted(pending));
        if (pending > 0) {
            target.append(" (가장 오래된 %d분)".formatted(oldestAgeSeconds / 60));
        }
        if (oldestAgeSeconds >= PENDING_AGE_ALERT.toSeconds()) {
            alerts.add("미반영이 %d분 넘게 밀려 있습니다.".formatted(PENDING_AGE_ALERT.toMinutes()));
        }

        if (last.isEmpty()) {
            target.append(" · 대조 기록 없음");
        } else {
            LastReconcile report = last.get();
            target.append(" · 마지막 대조 ").append(report.finishedAt().format(SHOWN));
            if (report.error() != null) {
                alerts.add("마지막 대조가 실패했습니다: " + report.error());
            } else {
                target.append(" 검사 %d곡 · 불일치 %d곡".formatted(report.checked(), report.mismatched()));
                if (!report.byReplica().isBlank()) {
                    target.append(" (").append(report.byReplica()).append(")");
                }
                if (report.mismatched() >= MISMATCH_ALERT) {
                    alerts.add("대조에서 %d곡이 어긋났습니다. 미반영에 넣었고 재시도가 메웁니다."
                        .formatted(report.mismatched()));
                }
            }
            if (Duration.between(report.finishedAt(), now).compareTo(RECONCILE_STALE_ALERT) > 0) {
                alerts.add("대조가 %d시간 넘게 돌지 않았습니다.".formatted(RECONCILE_STALE_ALERT.toHours()));
            }
        }

        return new AdminDependencyStatus(DEPENDENCY_NAME, alerts.isEmpty() ? "UP" : "WARN",
            target.toString(), latency, alerts.isEmpty() ? null : String.join(" ", alerts));
    }

    /**
     * Redis 에 남긴 마지막 대조. 필드 순서가 곧 형식이다 — 사유는 {@code |} 를 담을 수 있어
     * 맨 끝에 두고 나머지만 자른다.
     */
    record LastReconcile(LocalDateTime finishedAt, int checked, int mismatched,
                         String byReplica, String error) {
    }

    static String encode(ReconcileReport report) {
        String byReplica = report.mismatchByReplica().entrySet().stream()
            .map(entry -> entry.getKey() + " " + entry.getValue())
            .collect(Collectors.joining(", "));
        return String.join("|",
            report.finishedAt().toString(),
            String.valueOf(report.checkedTracks()),
            String.valueOf(report.mismatchCount()),
            byReplica,
            report.error() == null ? "" : report.error());
    }

    static Optional<LastReconcile> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", 5);
        if (parts.length < 5) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LastReconcile(
                LocalDateTime.parse(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
                parts[3],
                parts[4].isEmpty() ? null : parts[4]));
        } catch (RuntimeException e) {
            // 형식이 바뀐 옛 값. 다음 대조가 덮어쓴다.
            return Optional.empty();
        }
    }

    private Optional<LastReconcile> readLast() {
        try {
            return decode(cacheService.get(LAST_KEY, CacheKind.AnalyticsReconcileLast));
        } catch (RuntimeException e) {
            log.warn("대조 결과를 Redis 에서 읽지 못했다: {}", e.toString());
            return Optional.empty();
        }
    }

}
