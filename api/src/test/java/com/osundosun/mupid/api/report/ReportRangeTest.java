package com.osundosun.mupid.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.report.dto.ReportRange;
import com.osundosun.mupid.api.report.error.ReportErrorCode;

/**
 * 일별 리포트 기간 규칙 검증. **인프라 없이 돈다** — `ReportRange` 가 날짜 산술과
 * 경계 판단만 하기 때문이다({@code MubtiScorerTest} 와 같은 갈래).
 *
 * <h2>왜 경계를 이렇게 자세히 보는가</h2>
 *
 * 기간 파라미터는 <b>되돌리기 비싼 공개 계약</b>이다. 양끝 포함인지, 상한이 몇 일인지,
 * 넘으면 자르는지 거절하는지가 클라이언트 해석을 바꾼다. 여기서 못 박아 두면 나중에
 * 상수를 만질 때 무엇이 깨지는지 이 테스트가 말해 준다.
 *
 * `today` 를 인자로 받는 것도 같은 이유다. 규칙 안에서 `LocalDate.now()` 를 부르면
 * 자정 근처에서 테스트가 흔들린다.
 */
class ReportRangeTest {

    /** 경계를 고정한다. 자정 근처에 돌려도 결과가 같아야 한다. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    @DisplayName("양끝을 포함해 날짜를 하루씩 늘어놓는다")
    void includesBothEnds() {
        ReportRange range = ReportRange.of(TODAY.minusDays(2), TODAY, TODAY);

        assertEquals(
            List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY),
            range.days());
        assertEquals(TODAY.minusDays(2), range.from());
        assertEquals(TODAY, range.to());
    }

    @Test
    @DisplayName("하루짜리 기간도 한 칸이다")
    void singleDayIsOneSlot() {
        ReportRange range = ReportRange.of(TODAY, TODAY, TODAY);

        assertEquals(List.of(TODAY), range.days());
    }

    @Test
    @DisplayName("화면이 쓰는 7일 창은 그대로 통과한다")
    void weeklyWindowPasses() {
        // 주간 달력이 일요일부터 토요일까지 일곱 장을 그린다.
        ReportRange range = ReportRange.of(TODAY.minusDays(6), TODAY, TODAY);

        assertEquals(7, range.days().size());
        assertEquals(TODAY.minusDays(6), range.days().getFirst());
        assertEquals(TODAY, range.days().getLast());
    }

    @Test
    @DisplayName("상한 31일은 통과하고 32일은 거절한다")
    void maxDaysBoundary() {
        assertTrue(ReportRange.MAX_DAYS == 31, "상한이 바뀌면 이 테스트의 근거도 다시 본다");

        assertEquals(
            ReportRange.MAX_DAYS,
            ReportRange.of(TODAY.minusDays(ReportRange.MAX_DAYS - 1L), TODAY, TODAY).days().size());

        BusinessException thrown = assertThrows(
            BusinessException.class,
            () -> ReportRange.of(TODAY.minusDays(ReportRange.MAX_DAYS), TODAY, TODAY));

        assertEquals(ReportErrorCode.REPORT_RANGE_TOO_LONG, thrown.getErrorCode());
    }

    @Test
    @DisplayName("넘친 기간을 조용히 잘라 주지 않는다 — 잘린 답은 틀린 답이다")
    void refusesInsteadOfTruncating() {
        // 1년을 요청했다고 31일치를 돌려주면, 클라이언트는 그걸 1년치로 읽는다.
        assertThrows(
            BusinessException.class,
            () -> ReportRange.of(TODAY.minusYears(1), TODAY, TODAY));
    }

    @Test
    @DisplayName("뒤집힌 기간은 400 이다")
    void rejectsReversedRange() {
        BusinessException thrown = assertThrows(
            BusinessException.class,
            () -> ReportRange.of(TODAY, TODAY.minusDays(1), TODAY));

        assertEquals(ReportErrorCode.REPORT_INVALID_DATE, thrown.getErrorCode());
    }

    @Test
    @DisplayName("미래를 포함하면 400 이다")
    void rejectsFuture() {
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(TODAY, TODAY.plusDays(1), TODAY)).getErrorCode());

        // from 만 미래인 경우도 to.isAfter(today) 로 걸린다.
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(TODAY.plusDays(1), TODAY.plusDays(2), TODAY)).getErrorCode());
    }

    @Test
    @DisplayName("값이 없으면 400 이다 — 빈 값을 '오늘'로 읽지 않는다")
    void rejectsMissingEnds() {
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(null, TODAY, TODAY)).getErrorCode());
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(TODAY, null, TODAY)).getErrorCode());
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(null, null, TODAY)).getErrorCode());
    }

    @Test
    @DisplayName("미래이면서 너무 긴 요청은 날짜 오류로 답한다")
    void dateErrorWinsOverLength() {
        // 더 근본적인 잘못을 먼저 말해야 고치기 쉽다.
        assertEquals(
            ReportErrorCode.REPORT_INVALID_DATE,
            assertThrows(BusinessException.class,
                () -> ReportRange.of(TODAY.minusDays(400), TODAY.plusDays(400), TODAY)).getErrorCode());
    }

    @Test
    @DisplayName("월·연 경계를 넘는 창도 날짜 수가 맞는다")
    void crossesMonthAndYearBoundary() {
        LocalDate to = LocalDate.of(2026, 1, 2);
        ReportRange range = ReportRange.of(LocalDate.of(2025, 12, 30), to, to);

        assertEquals(
            List.of(LocalDate.of(2025, 12, 30), LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)),
            range.days());
    }

    @Test
    @DisplayName("days 는 밖에서 바꿀 수 없다")
    void daysIsImmutable() {
        ReportRange range = ReportRange.of(TODAY.minusDays(1), TODAY, TODAY);

        assertThrows(
            UnsupportedOperationException.class,
            () -> range.days().add(TODAY.plusDays(1)));
    }

}
