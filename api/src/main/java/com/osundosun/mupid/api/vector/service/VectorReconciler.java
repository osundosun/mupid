package com.osundosun.mupid.api.vector.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.service.AnalyticsSyncMonitor;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync;
import com.osundosun.mupid.api.analytics.sync.service.VectorSync;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.vector.client.VectorPointStore;
import com.osundosun.mupid.api.vector.client.VectorPointStore.Page;
import com.osundosun.mupid.api.vector.client.VectorPointStore.StoredPoint;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository.Fingerprint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 MySQL 과 Qdrant 를 곡 단위로 비교한다 (S15P21A204-457, 설계 §8.2-4·§7.4).
 *
 * <p>비교하는 것은 <b>구간 지문</b>이다 — 곡마다 {@code external_id} 집합, 구간마다 {@code analysis_version}·
 * {@code seg_start_ms}·{@code seg_end_ms}. 벡터 값은 보지 않는다(원본이 배치에 있어 Spring 이 맞다 틀리다를
 * 말할 수 없다). 어긋난 곡은 미반영 {@code VECTOR} 로 넣고, <b>배치가 그 목록을 읽어 다시 보낸다</b>
 * ({@code GET /api/v1/system/vectors/pending}). Spring 이 고치지 않는다.
 *
 * <ul>
 *   <li><b>살아 있는 곡에 포인트가 없거나 모자라거나 남거나 지문이 다르다</b> → 미반영에 넣는다.
 *       첫 배포에서는 1,060곡 전부가 여기 걸린다 — 그것이 배치의 첫 적재 목록이다.</li>
 *   <li><b>MySQL 에 살아 있지 않은 곡의 포인트</b>(삭제된 곡, 구간을 다 지운 곡) → Qdrant 에서 지운다
 *       (설계 §7.4 "대조 작업이 MySQL 목록과 비교해 물리 삭제한다"). 그 틈은 AI 가 ClickHouse
 *       {@code is_deleted} 로, 최종으로는 Spring 이 응답에서 막는다.</li>
 * </ul>
 *
 * <p><b>지우기는 조심한다.</b> MySQL 쪽이 비었거나 Qdrant 곡의 절반 넘게를 지우게 되면 지우지 않고 알린다 —
 * 연결이 엉뚱한 DB 를 보고 있을 때 컬렉션을 비우지 않게. 벡터는 배치가 다시 보내야만 돌아온다.
 *
 * <p>ClickHouse 대조(04:40·04:45) 뒤 04:50 에 돈다. Redis 선점 열쇠 {@code vectors:날짜}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorReconciler {

    static final List<String> PAYLOAD = List.of("external_id", "track_id", "analysis_version",
        "seg_start_ms", "seg_end_ms");
    static final String REPLICA = "qdrant";

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration LOCK_TTL = Duration.ofHours(6);
    private static final int PAGE = 1_000;
    private static final int LOG_SAMPLE = 20;
    /** 한 번에 지울 수 있는 곡의 비율 상한. 넘으면 지우지 않는다. */
    static final double MAX_DELETE_FRACTION = 0.5;

    private final QdrantProperties properties;
    private final VectorSectionRepository mysql;
    private final VectorPointStore store;
    private final VectorSync sync;
    private final AnalyticsSyncMonitor monitor;
    private final CacheService cacheService;

    /** 대조 한 번의 판단. DB·Qdrant 를 보지 않는다. */
    record Comparison(Set<TrackVersion> mismatched, Set<Integer> orphans, int qdrantTracks) {
    }

    /** 매일 04:50 KST. */
    @Scheduled(cron = "0 50 4 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        if (!properties.isConfigured()) {
            return;                     // 벡터 저장소가 없는 환경(dev). 알릴 것도 없다.
        }
        LocalDate today = LocalDate.now(ZONE);
        if (!cacheService.setIfAbsent("vectors:" + today, CacheKind.AnalyticsReconcileBatch, LOCK_TTL)) {
            log.info("벡터 대조를 다른 인스턴스가 이미 잡았다. date={}", today);
            return;
        }
        reconcile();
    }

    public ReconcileReport reconcile() {
        Map<Integer, Map<String, Fingerprint>> expected;
        Comparison result;
        try {
            expected = mysql.aliveFingerprints();
            result = compare(expected, readQdrant());
        } catch (RuntimeException e) {
            log.warn("벡터 대조를 하지 못했다. 미반영 기록은 그대로다.", e);
            ReconcileReport failed = ReconcileReport.failed(LocalDateTime.now(ZONE), TrackSectionSync.describe(e));
            monitor.recordReconcile(VectorSync.KIND, failed);
            return failed;
        }

        int enqueued = 0;
        for (TrackVersion track : result.mismatched()) {
            try {
                sync.enqueue(track.musicId(), track.analysisVersion());
                enqueued++;
            } catch (RuntimeException e) {
                log.warn("어긋난 곡을 벡터 미반영에 넣지 못했다. musicId={}", track.musicId(), e);
            }
        }
        deleteOrphans(expected, result);

        ReconcileReport report = new ReconcileReport(LocalDateTime.now(ZONE), expected.size(),
            result.mismatched(), Map.of(REPLICA, result.mismatched().size()), result.orphans().size(), 0, null);
        log.info("벡터 대조 완료 — 검사 {}곡 · 불일치 {}곡(미반영에 {}곡) · Qdrant 에만 {}곡{}",
            report.checkedTracks(), report.mismatchCount(), enqueued, result.orphans().size(),
            result.mismatched().isEmpty() ? "" : " — 예: " + result.mismatched().stream()
                .limit(LOG_SAMPLE).map(TrackVersion::musicId).toList());
        monitor.recordReconcile(VectorSync.KIND, report);
        return report;
    }

    private void deleteOrphans(Map<Integer, Map<String, Fingerprint>> expected, Comparison result) {
        if (result.orphans().isEmpty()) {
            return;
        }
        if (expected.isEmpty() || result.orphans().size() > MAX_DELETE_FRACTION * result.qdrantTracks()) {
            log.error("Qdrant 에만 있는 곡이 {}곡(Qdrant {}곡 중)이라 지우지 않았다 — MySQL 연결을 먼저 본다. 예: {}",
                result.orphans().size(), result.qdrantTracks(),
                result.orphans().stream().limit(LOG_SAMPLE).toList());
            return;
        }
        try {
            store.deleteTracks(result.orphans());
            log.info("MySQL 에 살아 있지 않은 곡 {}곡의 포인트를 지웠다 — 예: {}", result.orphans().size(),
                result.orphans().stream().limit(LOG_SAMPLE).toList());
        } catch (RuntimeException e) {
            log.warn("Qdrant 에만 있는 곡의 포인트를 지우지 못했다. 다음 대조가 다시 본다.", e);
        }
    }

    /** Qdrant 의 모든 포인트 지문. 곡 → (external_id → 지문). 벡터는 읽지 않는다. */
    private Map<Integer, Map<String, Fingerprint>> readQdrant() {
        Map<Integer, Map<String, Fingerprint>> out = new HashMap<>();
        Object offset = null;
        do {
            Page page = store.scroll(offset, PAGE, PAYLOAD);
            for (StoredPoint point : page.points()) {
                Map<String, Object> p = point.payload();
                Object track = p.get("track_id");
                if (!(track instanceof Number number)) {
                    continue;           // 정의 밖의 포인트(개발용 적재 등). 세지 않는다
                }
                out.computeIfAbsent(number.intValue(), id -> new LinkedHashMap<>())
                    .put(String.valueOf(p.get("external_id")), new Fingerprint(
                        p.get("analysis_version") == null ? null : String.valueOf(p.get("analysis_version")),
                        asLong(p.get("seg_start_ms")), asLong(p.get("seg_end_ms"))));
            }
            offset = page.next();
        } while (offset != null);
        return out;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    /**
     * 곡마다 구간 지문 집합이 같은가. MySQL 에 살아 있는 곡은 다르면 불일치, Qdrant 에만 있는 곡은 지울 곡.
     * 열쇠의 버전은 MySQL 의 것이다 — 배치가 그 버전의 벡터를 보내야 행이 지워진다.
     */
    static Comparison compare(Map<Integer, Map<String, Fingerprint>> mysql,
                              Map<Integer, Map<String, Fingerprint>> qdrant) {
        Set<TrackVersion> mismatched = new TreeSet<>(Comparator.comparingInt(TrackVersion::musicId));
        mysql.forEach((musicId, sections) -> {
            if (!sections.equals(qdrant.get(musicId))) {
                String version = sections.values().iterator().next().analysisVersion();
                mismatched.add(new TrackVersion(musicId, version));
            }
        });
        Set<Integer> orphans = new TreeSet<>(qdrant.keySet());
        orphans.removeAll(mysql.keySet());
        return new Comparison(mismatched, orphans, qdrant.size());
    }

}
