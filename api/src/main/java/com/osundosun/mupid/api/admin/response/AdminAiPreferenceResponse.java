package com.osundosun.mupid.api.admin.response;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 선호도 추이 — AI 가 추천한 곡을 사람들이 실제로 듣는가 (S15P21A204-427).
 *
 * <p>원본은 ClickHouse {@code ai_preference_hourly} 다. 한 시간에 한 번 배치가 채우므로
 * <b>지금 진행 중인 시간은 들어 있지 않다</b> — 창의 끝이 오늘이어도 마지막 칸은 직전
 * 정시에 끝난다. 지표 정의는 {@code knowledge/domain/ai-preference.md}.
 *
 * @param from              창의 시작일. 포함
 * @param to                창의 종료일. 포함
 * @param timeZone          시간 칸의 기준 시간대 ({@code Asia/Seoul})
 * @param engagedThresholdMs 선호 재생으로 치는 최소 청취 시간. 화면이 정의를 말할 때 쓴다
 * @param computedAt        이 창의 칸들이 마지막으로 계산된 시각 ({@code yyyy-MM-dd HH:mm:ss}).
 *                          한 칸도 계산된 적이 없으면 {@code null} — 표를 만들고 배치가
 *                          아직 한 번도 안 돌았다는 뜻이다
 * @param hours             가로축. 창의 모든 시간이 빠짐없이 온다
 * @param series            선들. AI 합계 → 구분별 → AI 아닌 재생(기준선) 순이다
 */
public record AdminAiPreferenceResponse(
    LocalDate from,
    LocalDate to,
    String timeZone,
    long engagedThresholdMs,
    String computedAt,
    List<AdminAiPreferenceHour> hours,
    List<AdminAiPreferenceSeries> series
) {

}
