package com.osundosun.mupid.api.admin.response;

import java.util.List;

/**
 * 그래프의 선 하나 (S15P21A204-427).
 *
 * @param key    {@code AI}(AI 합계) 또는 구분 이름({@code HOME}·{@code SECTION}·…·{@code NON_AI})
 * @param label  화면에 보일 이름
 * @param ai     AI 추천에서 나온 재생인가. {@code NON_AI} 만 거짓이다 — 비교 기준선
 * @param total  창 전체의 합
 * @param points 시간 칸별 수치. 응답의 {@code hours} 와 <b>같은 순서, 같은 길이</b>다
 */
public record AdminAiPreferenceSeries(
    String key,
    String label,
    boolean ai,
    AdminAiPreferenceStats total,
    List<AdminAiPreferenceStats> points
) {

}
