package com.osundosun.mupid.api.admin.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.admin.dto.AdminAiPreferenceRange;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceAggregateResponse;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceHour;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceResponse;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceSeries;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceStats;
import com.osundosun.mupid.api.analytics.dto.AiPreferenceHourRow;
import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;
import com.osundosun.mupid.api.analytics.repository.AiPreferenceRepository;
import com.osundosun.mupid.api.analytics.service.AiPreferenceBatch;
import com.osundosun.mupid.api.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AdminAiPreferenceService} 구현.
 *
 * <h2>질의 수는 창 길이와 무관하다</h2>
 *
 * ClickHouse 2회(칸 읽기 1 · 마지막 계산 시각 1). 이레든 한 달이든 같다.
 *
 * <h2>비율은 여기서 나눈다</h2>
 *
 * 표에는 건수만 있다. 합계 선(AI 합계)과 창 전체 합은 <b>분자와 분모를 따로 더한 뒤</b>
 * 나눈다 — 시간 칸의 비율을 평균 내면 재생 1회짜리 새벽 칸이 재생 백 회짜리 저녁 칸과 같은
 * 무게를 갖는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAiPreferenceServiceImpl implements AdminAiPreferenceService {

    /** AI 구분 넷을 더한 선의 키. */
    static final String AI_TOTAL_KEY = "AI";

    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final DateTimeFormatter TIME_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiPreferenceRepository repository;
    private final AiPreferenceBatch batch;

    @Override
    public AdminAiPreferenceResponse preference(AdminAiPreferenceRange range) {
        LocalDateTime start = range.from().atStartOfDay();
        LocalDateTime end = end(range, LocalDateTime.now(AiPreferenceRepository.ZONE));

        List<AiPreferenceHourRow> rows;
        Instant computedAt;
        try {
            rows = start.isBefore(end) ? repository.read(start, end) : List.of();
            computedAt = start.isBefore(end) ? repository.lastComputedAt(start, end) : null;
        } catch (DataAccessException e) {
            log.warn("AI 선호도 집계를 읽지 못했다. [{}, {})", start, end, e);
            throw new BusinessException(AdminErrorCode.ANALYTICS_UNAVAILABLE);
        }
        return build(range, start, end, rows, computedAt);
    }

    @Override
    public AdminAiPreferenceAggregateResponse aggregate(AdminAiPreferenceRange range) {
        try {
            int written = batch.backfill(range.from(), range.to());
            return new AdminAiPreferenceAggregateResponse(range.from(), range.to(), written);
        } catch (DataAccessException e) {
            log.warn("AI 선호도를 다시 집계하지 못했다. {} ~ {}", range.from(), range.to(), e);
            throw new BusinessException(AdminErrorCode.ANALYTICS_UNAVAILABLE);
        }
    }

    /**
     * 가로축의 끝(제외). 창의 끝 다음 날 자정이되, <b>지금 진행 중인 시간은 넣지 않는다</b> —
     * 배치가 끝난 시간만 계산하므로 넣으면 늘 "계산 안 됨" 칸이 하나 붙는다.
     */
    static LocalDateTime end(AdminAiPreferenceRange range, LocalDateTime now) {
        LocalDateTime end = range.toExclusive().atStartOfDay();
        LocalDateTime current = now.truncatedTo(ChronoUnit.HOURS);
        return end.isAfter(current) ? current : end;
    }

    /**
     * 쌓아 둔 칸들을 선으로 편다. 순수 함수라 ClickHouse 없이 검사한다.
     *
     * <p>{@code CUSTOM}·{@code UNTYPED} 는 <b>창 안에 재생이 있을 때만</b> 선으로 낸다 —
     * 늘 내면 대부분의 창에서 바닥에 붙은 선 두 개가 범례만 차지한다. AI 합계에는 늘 들어간다.
     */
    static AdminAiPreferenceResponse build(
        AdminAiPreferenceRange range,
        LocalDateTime start,
        LocalDateTime end,
        List<AiPreferenceHourRow> rows,
        Instant computedAt
    ) {
        Map<LocalDateTime, Map<AiPreferenceSegment, AiPreferenceHourRow>> byHour = new HashMap<>();
        Set<LocalDateTime> computed = new HashSet<>();
        for (AiPreferenceHourRow row : rows) {
            computed.add(row.hour());
            byHour.computeIfAbsent(row.hour(), hour -> new EnumMap<>(AiPreferenceSegment.class))
                .put(row.segment(), row);
        }

        List<LocalDateTime> hours = new ArrayList<>();
        for (LocalDateTime hour = start; hour.isBefore(end); hour = hour.plusHours(1)) {
            hours.add(hour);
        }

        // 비중(share)의 분모. 구분 다섯을 전부 더한 그 시간의 재생 수다.
        List<Long> allPlays = new ArrayList<>();
        long allPlaysTotal = 0;
        for (LocalDateTime hour : hours) {
            long plays = sum(byHour.get(hour), segment -> true).plays;
            allPlays.add(plays);
            allPlaysTotal += plays;
        }

        List<AdminAiPreferenceSeries> series = new ArrayList<>();
        series.add(series(AI_TOTAL_KEY, "AI 추천 전체", true, AiPreferenceSegment::ai,
            hours, byHour, allPlays, allPlaysTotal, false));
        for (AiPreferenceSegment segment : AiPreferenceSegment.values()) {
            AdminAiPreferenceSeries line = series(segment.name(), segment.label(), segment.ai(),
                candidate -> candidate == segment, hours, byHour, allPlays, allPlaysTotal, true);
            boolean optional = segment == AiPreferenceSegment.CUSTOM || segment == AiPreferenceSegment.UNTYPED;
            if (optional && line.total().plays() == 0) {
                continue;
            }
            series.add(line);
        }

        List<AdminAiPreferenceHour> axis = hours.stream()
            .map(hour -> new AdminAiPreferenceHour(hour.format(HOUR_LABEL), computed.contains(hour)))
            .toList();

        return new AdminAiPreferenceResponse(
            range.from(),
            range.to(),
            AiPreferenceRepository.ZONE.getId(),
            AiPreferenceRepository.ENGAGED_MS,
            computedAt == null ? null
                : LocalDateTime.ofInstant(computedAt, AiPreferenceRepository.ZONE).format(TIME_LABEL),
            axis,
            series);
    }

    /**
     * 선 하나. {@code single} 이면 구분 하나라 시간 칸의 {@code users} 를 그대로 싣는다.
     * 여러 구분을 더한 선과 창 전체 합에서는 {@code users} 를 비운다 — 같은 사람이 겹친다.
     */
    private static AdminAiPreferenceSeries series(
        String key,
        String label,
        boolean ai,
        Predicate<AiPreferenceSegment> include,
        List<LocalDateTime> hours,
        Map<LocalDateTime, Map<AiPreferenceSegment, AiPreferenceHourRow>> byHour,
        List<Long> allPlays,
        long allPlaysTotal,
        boolean single
    ) {
        List<AdminAiPreferenceStats> points = new ArrayList<>();
        Counts total = new Counts();
        for (int i = 0; i < hours.size(); i++) {
            Counts hour = sum(byHour.get(hours.get(i)), include);
            total.add(hour);
            points.add(hour.stats(single ? hour.users : null, allPlays.get(i)));
        }
        return new AdminAiPreferenceSeries(key, label, ai, total.stats(null, allPlaysTotal), points);
    }

    private static Counts sum(Map<AiPreferenceSegment, AiPreferenceHourRow> segments,
                              Predicate<AiPreferenceSegment> include) {
        Counts counts = new Counts();
        if (segments == null) {
            return counts;
        }
        segments.forEach((segment, row) -> {
            if (include.test(segment)) {
                counts.add(row);
            }
        });
        return counts;
    }

    /** 더할 수 있는 건수만 모으는 칸. {@code users} 는 구분 하나일 때만 뜻이 있다. */
    private static final class Counts {

        long plays;
        long users;
        long engaged;
        long completed;
        long skipped;
        long earlySkip;
        long liked;
        long listenedMs;

        void add(AiPreferenceHourRow row) {
            plays += row.plays();
            users += row.users();
            engaged += row.engagedPlays();
            completed += row.completedPlays();
            skipped += row.skippedPlays();
            earlySkip += row.earlySkipPlays();
            liked += row.likedPlays();
            listenedMs += row.listenedMs();
        }

        void add(Counts other) {
            plays += other.plays;
            users += other.users;
            engaged += other.engaged;
            completed += other.completed;
            skipped += other.skipped;
            earlySkip += other.earlySkip;
            liked += other.liked;
            listenedMs += other.listenedMs;
        }

        AdminAiPreferenceStats stats(Long users, long allPlays) {
            return new AdminAiPreferenceStats(
                plays, users, engaged, completed, skipped, earlySkip, liked, listenedMs,
                ratio(engaged, plays),
                ratio(completed, plays),
                ratio(skipped, plays),
                ratio(earlySkip, plays),
                ratio(liked, plays),
                ratio(plays, allPlays));
        }

    }

    /** 분모가 0 이면 {@code null}. 소수 넷째 자리에서 자른다 — 응답이 불필요하게 길어진다. */
    static Double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round((double) numerator / denominator * 10_000d) / 10_000d;
    }

}
