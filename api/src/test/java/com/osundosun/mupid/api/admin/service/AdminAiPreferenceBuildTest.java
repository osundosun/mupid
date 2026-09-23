package com.osundosun.mupid.api.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.admin.dto.AdminAiPreferenceRange;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceResponse;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceSeries;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceStats;
import com.osundosun.mupid.api.analytics.dto.AiPreferenceHourRow;
import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * AI 선호도 그래프 응답을 만드는 규칙 (S15P21A204-427). <b>인프라 없이 돈다</b> —
 * {@code build} 가 쌓아 둔 칸을 받아 선으로 펴기만 하는 순수 함수라서다.
 *
 * <h2>무엇을 못박는가</h2>
 *
 * <ol>
 *   <li><b>합계의 비율은 건수를 먼저 더한 뒤 나눈다</b> — 칸의 비율을 평균 내면 재생 1회짜리
 *       칸이 100회짜리 칸과 같은 무게가 된다.</li>
 *   <li><b>분모가 0 이면 비율은 null</b> — 0% 로 보내면 "들었는데 아무도 안 좋아했다" 로 읽힌다.</li>
 *   <li><b>가로축은 빈틈이 없고, 계산 안 된 칸을 표시한다</b> — "0회" 와 "모른다" 가 다르다.</li>
 *   <li><b>진행 중인 시간은 넣지 않는다.</b></li>
 *   <li><b>창의 기본값과 상한</b> — 이레, 31일, 한쪽만 주면 거절.</li>
 * </ol>
 */
class AdminAiPreferenceBuildTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final LocalDateTime MIDNIGHT = DAY.atStartOfDay();
    private static final AdminAiPreferenceRange ONE_DAY = new AdminAiPreferenceRange(DAY, DAY);

    @Test
    @DisplayName("AI 합계 선은 AI 구분의 건수를 더한 뒤 비율을 낸다")
    void aiTotalSumsCountsBeforeDividing() {
        List<AiPreferenceHourRow> rows = List.of(
            // 00시: HOME 1회 중 1회 선호, SECTION 99회 중 0회 선호
            row(MIDNIGHT, AiPreferenceSegment.HOME, 1, 1, 1, 0),
            row(MIDNIGHT, AiPreferenceSegment.SECTION, 99, 5, 0, 10),
            row(MIDNIGHT, AiPreferenceSegment.NON_AI, 100, 7, 50, 0));

        AdminAiPreferenceResponse response = build(MIDNIGHT.plusHours(1), rows);

        AdminAiPreferenceStats ai = series(response, "AI").points().get(0);
        assertEquals(100, ai.plays());
        assertEquals(1, ai.engagedPlays());
        // 평균(0.5 + 0.0) / 2 = 0.5 가 아니라 1 / 100 이다.
        assertEquals(0.01, ai.engagedRate());
        assertEquals(0.1, ai.skipRate());
        // 비중: AI 100회 / 전체 200회
        assertEquals(0.5, ai.share());
        // 여러 구분을 더한 선은 사람 수를 비운다 — 같은 사람이 겹친다.
        assertNull(ai.users());

        AdminAiPreferenceStats home = series(response, "HOME").points().get(0);
        assertEquals(1L, home.users());
        assertEquals(1.0, home.engagedRate());
    }

    @Test
    @DisplayName("재생이 없는 칸의 비율은 null 이고 건수는 0 이다")
    void emptyCellHasNullRates() {
        AdminAiPreferenceResponse response = build(MIDNIGHT.plusHours(2), List.of(
            row(MIDNIGHT, AiPreferenceSegment.NON_AI, 10, 2, 5, 0)));

        AdminAiPreferenceStats aiAtZero = series(response, "AI").points().get(0);
        assertEquals(0, aiAtZero.plays());
        assertNull(aiAtZero.engagedRate());
        // 전체 재생은 있었으므로 비중은 0 이다 (null 이 아니다).
        assertEquals(0.0, aiAtZero.share());

        AdminAiPreferenceStats aiAtOne = series(response, "AI").points().get(1);
        assertNull(aiAtOne.engagedRate());
        assertNull(aiAtOne.share());
    }

    @Test
    @DisplayName("창 전체 합도 건수를 더해서 나눈다")
    void totalAcrossHours() {
        AdminAiPreferenceResponse response = build(MIDNIGHT.plusHours(2), List.of(
            row(MIDNIGHT, AiPreferenceSegment.SECTION, 1, 1, 1, 0),
            row(MIDNIGHT.plusHours(1), AiPreferenceSegment.SECTION, 3, 1, 0, 3)));

        AdminAiPreferenceStats total = series(response, "SECTION").total();
        assertEquals(4, total.plays());
        assertEquals(0.25, total.engagedRate());
        assertEquals(0.75, total.skipRate());
        assertNull(total.users());
    }

    @Test
    @DisplayName("가로축은 빈틈없이 오고, 배치가 쓴 칸만 computed 다")
    void axisMarksComputedHours() {
        // 01시만 계산됐다 (재생이 없어도 0 행이 있으면 계산된 것이다).
        AdminAiPreferenceResponse response = build(MIDNIGHT.plusHours(3), List.of(
            row(MIDNIGHT.plusHours(1), AiPreferenceSegment.NON_AI, 0, 0, 0, 0)));

        assertEquals(3, response.hours().size());
        assertEquals("2026-09-22 00:00", response.hours().get(0).hour());
        assertFalse(response.hours().get(0).computed());
        assertTrue(response.hours().get(1).computed());
        assertFalse(response.hours().get(2).computed());
        response.series().forEach(line -> assertEquals(3, line.points().size()));
    }

    @Test
    @DisplayName("CUSTOM·UNTYPED 는 재생이 있을 때만 선이 된다. 기준선은 늘 마지막이다")
    void optionalSeriesOnlyWhenPresent() {
        AdminAiPreferenceResponse empty = build(MIDNIGHT.plusHours(1), List.of());
        assertEquals(List.of("AI", "HOME", "SECTION", "NON_AI"), keys(empty));

        AdminAiPreferenceResponse untyped = build(MIDNIGHT.plusHours(1), List.of(
            row(MIDNIGHT, AiPreferenceSegment.UNTYPED, 2, 1, 1, 0)));
        assertEquals(List.of("AI", "HOME", "SECTION", "UNTYPED", "NON_AI"), keys(untyped));
        // AI 합계에는 모르는 유형도 들어간다 — 빠지면 추천 성과가 작아 보인다.
        assertEquals(2, series(untyped, "AI").total().plays());
    }

    @Test
    @DisplayName("계산 시각이 없으면 null, 있으면 서울 시각 글자다")
    void computedAtLabel() {
        assertNull(build(MIDNIGHT.plusHours(1), List.of()).computedAt());

        Instant at = LocalDateTime.of(2026, 9, 22, 11, 5, 3)
            .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        AdminAiPreferenceResponse response = AdminAiPreferenceServiceImpl.build(
            ONE_DAY, MIDNIGHT, MIDNIGHT.plusHours(1), List.of(), at);
        assertEquals("2026-09-22 11:05:03", response.computedAt());
    }

    @Test
    @DisplayName("오늘이 끝인 창은 진행 중인 시간 앞에서 멈춘다")
    void endStopsBeforeCurrentHour() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 22, 14, 37);
        assertEquals(LocalDateTime.of(2026, 9, 22, 14, 0), AdminAiPreferenceServiceImpl.end(ONE_DAY, now));

        AdminAiPreferenceRange past = new AdminAiPreferenceRange(DAY.minusDays(2), DAY.minusDays(1));
        assertEquals(MIDNIGHT, AdminAiPreferenceServiceImpl.end(past, now));
    }

    @Test
    @DisplayName("비율은 소수 넷째 자리에서 자르고 분모 0 은 null 이다")
    void ratioRounding() {
        assertEquals(0.3333, AdminAiPreferenceServiceImpl.ratio(1, 3));
        assertNull(AdminAiPreferenceServiceImpl.ratio(0, 0));
    }

    // -------------------------------------------------------------------- 창

    @Test
    @DisplayName("창의 기본값은 오늘로 끝나는 이레다")
    void rangeDefaultsToSevenDays() {
        AdminAiPreferenceRange range = AdminAiPreferenceRange.of(null, null, DAY);
        assertEquals(DAY, range.to());
        assertEquals(DAY.minusDays(6), range.from());
        assertEquals(DAY.plusDays(1), range.toExclusive());
    }

    @Test
    @DisplayName("한쪽만 주거나 뒤집히거나 미래이거나 31일을 넘으면 거절한다")
    void rangeRejectsInvalidWindows() {
        assertInvalid(() -> AdminAiPreferenceRange.of(DAY, null, DAY));
        assertInvalid(() -> AdminAiPreferenceRange.of(DAY, DAY.minusDays(1), DAY));
        assertInvalid(() -> AdminAiPreferenceRange.of(DAY, DAY.plusDays(1), DAY));
        assertInvalid(() -> AdminAiPreferenceRange.of(DAY.minusDays(31), DAY, DAY));

        AdminAiPreferenceRange widest = AdminAiPreferenceRange.of(DAY.minusDays(30), DAY, DAY);
        assertEquals(AdminAiPreferenceRange.MAX_DAYS,
            widest.from().datesUntil(widest.toExclusive()).count());
    }

    // ------------------------------------------------------------------ 도우미

    private static AdminAiPreferenceResponse build(LocalDateTime end, List<AiPreferenceHourRow> rows) {
        return AdminAiPreferenceServiceImpl.build(ONE_DAY, MIDNIGHT, end, rows, null);
    }

    private static AdminAiPreferenceSeries series(AdminAiPreferenceResponse response, String key) {
        return response.series().stream().filter(line -> line.key().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> keys(AdminAiPreferenceResponse response) {
        return response.series().stream().map(AdminAiPreferenceSeries::key).toList();
    }

    private static AiPreferenceHourRow row(LocalDateTime hour, AiPreferenceSegment segment,
                                           long plays, long users, long engaged, long skipped) {
        return new AiPreferenceHourRow(hour, segment, plays, users, engaged, 0, skipped, 0, 0, 0);
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable call) {
        BusinessException error = assertThrows(BusinessException.class, call);
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, error.getErrorCode());
    }

}
