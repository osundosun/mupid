package com.osundosun.mupid.api.admin.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * AI 선호도 그래프의 <b>조회 창</b> (S15P21A204-427).
 *
 * <p>{@link AdminUserTimelineRange} 와 같은 규칙이고 같은 이유로 값 객체다 — 날짜 산술과
 * 경계 판단뿐이라 ClickHouse 없이 검사할 수 있어야 한다. 시간대는 호출부가 정한다
 * (언제나 {@code Asia/Seoul}).
 *
 * @param from 시작일. 포함
 * @param to   종료일. 포함
 */
public record AdminAiPreferenceRange(
    LocalDate from,
    LocalDate to
) {

    /**
     * 아무것도 지정하지 않았을 때 덮는 날수 (오늘 포함).
     *
     * <p>이레면 요일마다 다른 청취 습관이 한 번씩 다 들어온다. 시간 칸 168개라 선 하나가
     * 아직 읽힌다.
     */
    public static final int DEFAULT_DAYS = 7;

    /**
     * 한 번에 덮을 수 있는 최대 날수.
     *
     * <p>상한을 정하는 것은 화면이다. 시간 단위 선이라 한 달(744칸)을 넘기면 칸 하나가 1px
     * 아래로 내려가 선이 뭉개진다. 다시 집계(`POST …/aggregate`)도 같은 상한을 쓴다 —
     * 요청 하나가 원본을 하루씩 31번 훑는 것이 nginx 의 60초 안에 넉넉히 끝나는 크기다.
     */
    public static final int MAX_DAYS = 31;

    /**
     * 창을 검증하고 만든다.
     *
     * <p><b>{@code from}·{@code to} 는 둘 다 있거나 둘 다 없어야 한다.</b> 한쪽만 받아
     * 나머지를 서버가 지어내면 화면은 자기가 요청한 창을 받았다고 믿는다. 둘 다 없으면
     * 오늘로 끝나는 {@link #DEFAULT_DAYS} 일이다.
     *
     * @param today 오늘 ({@code Asia/Seoul}). 테스트가 경계를 고정할 수 있게 인자로 받는다
     * @throws BusinessException {@link AdminErrorCode#INVALID_DATE_RANGE} — 한쪽만 주거나,
     *                           뒤집혔거나, 미래를 포함하거나, {@link #MAX_DAYS} 를 넘을 때
     */
    public static AdminAiPreferenceRange of(LocalDate from, LocalDate to, LocalDate today) {
        LocalDate start = from;
        LocalDate end = to;
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
        return new AdminAiPreferenceRange(start, end);
    }

    /** 반열린 구간의 오른쪽 끝 — {@code to} 가 포함이므로 하루 뒤다. */
    public LocalDate toExclusive() {
        return to.plusDays(1);
    }

}
