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

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;
import com.osundosun.mupid.api.analytics.repository.TrackFeatureRepository;
import com.osundosun.mupid.api.analytics.sync.dto.ReconcileReport;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.TrackFeatureSource;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 MySQL 과 ClickHouse <b>레플리카마다</b> 곡 특징을 비교해, 어긋난 곡을 미반영 기록에
 * 넣는다 (S15P21A204-455, 설계 §8.2-4). {@link TrackSectionReconciler} 의 곡 특징판이다.
 *
 * <p>비교 단위는 곡 하나의 특징 체크섬({@link TrackFeatureRow#digest})이다. MySQL 쪽은
 * {@link TrackFeatureSource#loadAll}, ClickHouse 쪽은 {@code FINAL} 로 읽은 행이고, <b>같은 자바
 * 함수가 양쪽을 센다.</b>
 *
 * <h2>처음 한 번 채우기(백필)도 이것이 한다</h2>
 *
 * <p>표를 막 만든 클러스터에서는 모든 곡이 "ClickHouse 에 없음" 으로 어긋나 미반영에 들어가고,
 * 재시도가 1분에 200곡씩 채운다(운영 1,060곡이면 6분). 채우는 길을 따로 두지 않는다 — 고치는 길이
 * 하나여야 한 번 확인한 것으로 끝난다. 다음 04:45 를 기다리기 싫으면 미반영 행을 손으로 넣는다
 * ({@code knowledge/architecture/analytics-sync.md} "운영에서 손으로 할 일").
 *
 * <h2>구간 대조보다 5분 뒤에 돈다</h2>
 *
 * <p>둘 다 레플리카 두 개를 훑는다. 겹치지 않게 떼어 두고, 선점 열쇠도 따로다({@code features:날짜}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrackFeatureReconciler {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration LOCK_TTL = Duration.ofHours(6);
    private static final int LOG_SAMPLE = 20;

    private final TrackFeatureSource mysql;
    private final TrackFeatureRepository clickHouse;
    private final TrackFeatureSync sync;
    private final AnalyticsSyncMonitor monitor;
    private final CacheService cacheService;

    /** 매일 04:45 KST. 구간 대조(04:40) 뒤다. */
    @Scheduled(cron = "0 45 4 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        LocalDate today = LocalDate.now(ZONE);
        if (!cacheService.setIfAbsent("features:" + today, CacheKind.AnalyticsReconcileBatch, LOCK_TTL)) {
            log.info("곡 특징 대조를 다른 인스턴스가 이미 잡았다. date={}", today);
            return;
        }
        reconcile();
    }

    /**
     * 대조하고, 어긋난 곡을 미반영에 넣고, 결과를 지표로 남긴다. ClickHouse 가 안 닿으면
     * 아무것도 넣지 않는다 — 반쪽 결과로는 "없다" 와 "못 읽었다" 를 가르지 못한다.
     */
    public ReconcileReport reconcile() {
        ReconcileReport report;
        try {
            Map<Integer, Long> expected = digests(mysql.loadAll());
            Map<String, Map<Integer, Long>> replicas = new LinkedHashMap<>();
            clickHouse.rowsByReplica().forEach((replica, rows) -> replicas.put(replica, digests(rows)));
            report = compare(expected, replicas, LocalDateTime.now(ZONE));
        } catch (RuntimeException e) {
            log.warn("곡 특징 대조를 하지 못했다. 미반영 기록은 그대로다.", e);
            report = ReconcileReport.failed(LocalDateTime.now(ZONE), TrackSectionSync.describe(e));
            monitor.recordReconcile(AnalyticsSyncKind.TRACK_FEATURES, report);
            return report;
        }

        int enqueued = 0;
        for (TrackVersion track : report.mismatchedTracks()) {
            try {
                sync.enqueue(track.musicId());
                enqueued++;
            } catch (RuntimeException e) {
                log.warn("어긋난 곡을 미반영에 넣지 못했다. musicId={}", track.musicId(), e);
            }
        }
        log.info("곡 특징 대조 완료 — 검사 {}곡 · 불일치 {}곡(미반영에 {}곡) · 레플리카별 {} · "
                + "ClickHouse 에만 {}곡{}",
            report.checkedTracks(), report.mismatchCount(), enqueued, report.mismatchByReplica(),
            report.onlyInClickHouse(),
            report.mismatchedTracks().isEmpty() ? "" : " — 예: " + report.mismatchedTracks().stream()
                .limit(LOG_SAMPLE).map(TrackVersion::musicId).toList());
        monitor.recordReconcile(AnalyticsSyncKind.TRACK_FEATURES, report);
        return report;
    }

    private static Map<Integer, Long> digests(Map<Integer, TrackFeatureRow> rows) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        rows.forEach((musicId, row) -> out.put(musicId, row.digest()));
        return out;
    }

    /**
     * 두 쪽 체크섬을 비교한다. DB 를 보지 않는다 — 판단만 따로 시험하려고 떼어 둔다.
     *
     * <p>MySQL 의 곡마다, 레플리카마다 없거나 체크섬이 다르면 불일치. ClickHouse 에만 있는 곡은
     * 세기만 한다(곡은 논리 삭제라 MySQL 에서 행이 사라질 일이 없다 — 있다면 사람이 볼 일이다).
     * 레플리카가 하나도 없으면 전 곡 불일치다. 열쇠의 버전 칸은 비워 둔다 — 곡 특징은 곡마다
     * 한 행이다.
     */
    static ReconcileReport compare(Map<Integer, Long> mysql, Map<String, Map<Integer, Long>> replicas,
                                   LocalDateTime finishedAt) {
        Set<TrackVersion> mismatched = new TreeSet<>(Comparator.comparingInt(TrackVersion::musicId));
        Map<String, Integer> byReplica = new LinkedHashMap<>();
        if (replicas.isEmpty()) {
            mysql.keySet().forEach(id -> mismatched.add(key(id)));
        }
        for (Map.Entry<String, Map<Integer, Long>> replica : replicas.entrySet()) {
            int count = 0;
            for (Map.Entry<Integer, Long> expected : mysql.entrySet()) {
                if (!expected.getValue().equals(replica.getValue().get(expected.getKey()))) {
                    mismatched.add(key(expected.getKey()));
                    count++;
                }
            }
            byReplica.put(replica.getKey(), count);
        }
        Set<Integer> onlyInClickHouse = new HashSet<>();
        replicas.values().forEach(rows -> rows.keySet().stream()
            .filter(id -> !mysql.containsKey(id)).forEach(onlyInClickHouse::add));
        return new ReconcileReport(finishedAt, mysql.size(), mismatched, byReplica,
            onlyInClickHouse.size(), 0, null);
    }

    private static TrackVersion key(int musicId) {
        return new TrackVersion(musicId, TrackFeatureSync.NO_VERSION);
    }

}
