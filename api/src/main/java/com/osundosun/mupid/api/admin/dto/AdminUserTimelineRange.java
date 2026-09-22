package com.osundosun.mupid.api.admin.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * 사용자 행동 타임라인의 **조회 창** — 날짜 막대가 덮는 기간과, 그중 시간축으로 펼칠
 * 하루 (S15P21A204-430).
 *
 * <h2>왜 값 객체로 떼어 냈는가</h2>
 *
 * {@code ReportRange} 와 같은 이유다. 여기 있는 것은 <b>날짜 산술과 경계 판단</b>뿐이라
 * ClickHouse 도 리포지토리도 필요 없다. 서비스 안에 두면 경계값(하루 차이·상한 ±1)을
 * 검사하려고 스프링 컨텍스트와 분석 클러스터를 띄워야 하고, 그러면 아무도 그 검사를
 * 돌리지 않게 된다.
 *
 * <h2>시간대</h2>
 *
 * 여기서 다루지 않는다. {@link LocalDate} 는 이미 날짜이고 <b>어느 시간대의 날짜인지는
 * 호출부가 정한다</b> — 지금은 언제나 {@code Asia/Seoul} 이다. 하루의 경계가 ClickHouse
 * {@code DateTime64(3, 'Asia/Seoul')} 과 같아야 하므로 그 값을 여기서 바꿔 끼우지 않는다.
 *
 * @param from 시작일. 포함
 * @param to   종료일. 포함
 * @param date 시간축으로 펼칠 하루. <b>비어 있을 수 있다</b> — 그때는 서비스가 활동이
 *             있었던 가장 최근 날로 정한다. 날짜 목록을 받아 봐야 알 수 있는 값이라
 *             여기서 정하지 않는다
 */
public record AdminUserTimelineRange(
    LocalDate from,
    LocalDate to,
    LocalDate date
) {

    /**
     * 아무것도 지정하지 않았을 때 덮는 날수 (오늘 포함).
     *
     * <p>한 달이면 "요즘 이 사람이 언제 쓰는가" 가 보인다. 그보다 짧으면 주 단위 습관이
     * 안 보이고, 길면 막대가 가늘어져 어느 날을 눌러야 할지 고르기 어렵다.
     */
    public static final int DEFAULT_DAYS = 30;

    /**
     * 한 번에 덮을 수 있는 최대 날수.
     *
     * <p>{@code ReportRange.MAX_DAYS}(31) 보다 큰 이유는 <b>질의 비용이 날수에 비례하지
     * 않기 때문</b>이다. 그쪽은 스냅샷이 없는 날마다 집계를 다시 돌리지만(backfill-on-read),
     * 여기 날짜 막대는 기간 전체를 <b>{@code GROUP BY} 한 번</b>으로 센다.
     *
     * <p>그래서 상한을 정하는 것은 DB 가 아니라 화면이다. 한 분기(92일)면 막대 하나가
     * 아직 누를 만한 폭으로 남는다. 그보다 길면 막대가 1~2px 이 되어 <b>날을 고르는</b>
     * 이 화면의 쓰임 자체가 사라진다.
     */
    public static final int MAX_DAYS = 92;

    /**
     * 창을 검증하고 만든다.
     *
     * <p><b>{@code from}·{@code to} 는 둘 다 있거나 둘 다 없어야 한다.</b> 한쪽만 주면
     * 나머지를 서버가 지어내게 되는데, 그러면 화면은 자기가 요청한 창을 받았다고 믿는다 —
     * 어느 기간을 보고 있는지 모르는 채로 "이 기간엔 기록이 없다" 를 읽게 된다.
     * 둘 다 없으면 기본 창(오늘로 끝나는 {@link #DEFAULT_DAYS} 일)이라고 분명히 정해 둔다.
     *
     * <p>{@code date} 가 창 밖이면 거절한다. 조용히 당겨 넣으면 화면이 고른 날과 서버가
     * 그린 날이 달라지고, 그 어긋남은 <b>다른 날의 기록을 그 날의 것으로 읽게</b> 만든다.
     *
     * @param today 오늘. {@code Asia/Seoul} 기준이며 호출부가 넘긴다. 테스트가 경계를
     *              고정할 수 있도록 인자로 받는다 — 안에서 {@code LocalDate.now()} 를
     *              부르면 자정 근처에서 검사가 흔들린다
     * @throws BusinessException {@link AdminErrorCode#INVALID_DATE_RANGE} — 한쪽만 주거나,
     *                           뒤집혔거나, 미래를 포함하거나, {@link #MAX_DAYS} 를 넘거나,
     *                           {@code date} 가 창 밖일 때
     */
    public static AdminUserTimelineRange of(
        LocalDate from,
        LocalDate to,
        LocalDate date,
        LocalDate today
    ) {
        LocalDate end = to;
        LocalDate start = from;
        if (start == null && end == null) {
            end = today;
            start = today.minusDays(DEFAULT_DAYS - 1L);
        }
        if (start == null || end == null) {
            throw new BusinessException(AdminErrorCode.INVALID_DATE_RANGE);
        }
        if (start.isAfter(end) || end.isAfter(today)) {
            throw new BusinessException(AdminErrorCode.INVALID_DATE_RANGE);
        }
        // 양끝을 포함하므로 하루짜리 창의 길이는 1 이다.
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BusinessException(AdminErrorCode.INVALID_DATE_RANGE);
        }
        if (date != null && (date.isBefore(start) || date.isAfter(end))) {
            throw new BusinessException(AdminErrorCode.INVALID_DATE_RANGE);
        }
        return new AdminUserTimelineRange(start, end, date);
    }

    /**
     * 조회에 넘길 <b>끝의 다음 날</b>. {@code to} 가 포함이므로 반열린 구간의 오른쪽 끝은
     * 하루 뒤다.
     *
     * <p>ClickHouse 쪽 조건이 {@code occurred_at < ?} 인데 {@code to} 를 그대로 넘기면
     * <b>마지막 날이 통째로 빠진다.</b> 한 곳에서 계산해 두고 호출부가 되풀이하지 않는다.
     */
    public LocalDate toExclusive() {
        return to.plusDays(1);
    }

}
