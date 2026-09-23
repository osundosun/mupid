package com.osundosun.mupid.api.admin.response;

import java.time.LocalDate;

/**
 * AI 선호도를 다시 집계한 결과 (S15P21A204-427).
 *
 * @param from    다시 센 시작일. 포함
 * @param to      다시 센 종료일. 포함. 오늘이면 직전 정시까지만 셌다
 * @param written 쓴 칸 수 (시간 × 구분). 재생이 없던 칸도 0 으로 쓰므로 창의 길이에 비례한다
 */
public record AdminAiPreferenceAggregateResponse(
    LocalDate from,
    LocalDate to,
    int written
) {

}
