package com.osundosun.mupid.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.admin.dto.AdminUserTimelineRange;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * 사용자 행동 타임라인의 기간 규칙 (S15P21A204-430). **인프라 없이 돈다** —
 * {@link AdminUserTimelineRange} 가 날짜 산술과 경계 판단만 하기 때문이다
 * ({@code ReportRangeTest} 와 같은 갈래).
 *
 * <h2>무엇을 못박는가</h2>
 *
 * <ol>
 *   <li><b>{@code from}·{@code to} 는 둘 다거나 둘 다 없다</b> — 한쪽만 받아 나머지를
 *       서버가 지어내면, 화면은 자기가 요청한 창을 받았다고 믿은 채 다른 기간을 본다.</li>
 *   <li><b>{@code date} 는 창 안이어야 한다</b> — 조용히 당겨 넣으면 화면이 고른 날과
 *       서버가 그린 날이 달라져, 다른 날의 기록을 그 날의 것으로 읽게 된다.</li>
 *   <li><b>상한을 넘으면 자르지 않고 거절한다</b> — 1년을 요청했는데 석 달치를 돌려주면
 *       "이 기간엔 기록이 없다" 로 보이는데 실제로는 잘린 것이다.</li>
 *   <li><b>{@code toExclusive} 가 마지막 날을 삼키지 않는다</b> — ClickHouse 조건이
 *       {@code occurred_at < ?} 라, 여기서 하루를 더하지 않으면 마지막 날이 통째로
 *       빠진다. 조용히 틀린 값이 나오는 부류다.</li>
 * </ol>
 */
class AdminUserTimelineRangeTest {

    /** 경계를 고정한다. 자정 근처에 돌려도 결과가 같아야 한다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    @Test
    @DisplayName("둘 다 없으면 오늘로 끝나는 기본 창이다")
    void defaultsToRecentWindow() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(null, null, null, TODAY);

        assertEquals(TODAY, range.to());
        assertEquals(TODAY.minusDays(AdminUserTimelineRange.DEFAULT_DAYS - 1L), range.from());
        // 날짜를 여기서 정하지 않는다 — 활동이 있었던 날은 조회해 봐야 안다.
        assertNull(range.date());
    }

    @Test
    @DisplayName("기본 창의 길이는 양끝을 포함해 DEFAULT_DAYS 일이다")
    void defaultWindowIncludesBothEnds() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(null, null, null, TODAY);

        long length = range.from().datesUntil(range.to().plusDays(1)).count();
        assertEquals(AdminUserTimelineRange.DEFAULT_DAYS, length);
    }

    @Test
    @DisplayName("한쪽만 주면 거절한다 — 나머지를 서버가 지어내지 않는다")
    void rejectsHalfWindow() {
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(TODAY.minusDays(3), null, null, TODAY)));
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(null, TODAY, null, TODAY)));
    }

    @Test
    @DisplayName("뒤집힌 창과 미래를 포함한 창을 거절한다")
    void rejectsReversedAndFuture() {
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(TODAY, TODAY.minusDays(1), null, TODAY)));
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(TODAY, TODAY.plusDays(1), null, TODAY)));
    }

    @Test
    @DisplayName("상한 딱 맞는 창은 통과하고 하루 더 긴 창은 거절한다")
    void boundsAtMaxDays() {
        LocalDate longest = TODAY.minusDays(AdminUserTimelineRange.MAX_DAYS - 1L);
        assertEquals(longest, AdminUserTimelineRange.of(longest, TODAY, null, TODAY).from());

        LocalDate tooLong = longest.minusDays(1);
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(tooLong, TODAY, null, TODAY)));
    }

    @Test
    @DisplayName("하루짜리 창도 만들어진다")
    void singleDayWindow() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(TODAY, TODAY, TODAY, TODAY);

        assertEquals(TODAY, range.from());
        assertEquals(TODAY, range.to());
        assertEquals(TODAY, range.date());
    }

    @Test
    @DisplayName("창 밖의 날은 당겨 넣지 않고 거절한다")
    void rejectsDateOutsideWindow() {
        LocalDate from = TODAY.minusDays(6);

        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(from, TODAY, from.minusDays(1), TODAY)));
        assertEquals(AdminErrorCode.INVALID_DATE_RANGE, codeOf(
            () -> AdminUserTimelineRange.of(from, TODAY, TODAY.plusDays(1), TODAY)));
    }

    @Test
    @DisplayName("창의 양끝 날짜는 고를 수 있다")
    void acceptsDateOnWindowEdges() {
        LocalDate from = TODAY.minusDays(6);

        assertEquals(from, AdminUserTimelineRange.of(from, TODAY, from, TODAY).date());
        assertEquals(TODAY, AdminUserTimelineRange.of(from, TODAY, TODAY, TODAY).date());
    }

    @Test
    @DisplayName("toExclusive 는 하루 뒤다 — 마지막 날을 삼키지 않는다")
    void toExclusiveIsNextDay() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            TODAY.minusDays(2), TODAY, null, TODAY);

        assertEquals(TODAY.plusDays(1), range.toExclusive());
    }

    private static AdminErrorCode codeOf(Runnable call) {
        BusinessException thrown = assertThrows(BusinessException.class, call::run);
        return (AdminErrorCode)thrown.getErrorCode();
    }

}
