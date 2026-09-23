package com.osundosun.mupid.api.ai.response;

import java.util.List;

/**
 * 추천 색인의 한 순간에 대응하는 구간. <b>{@code ROLE_SYSTEM} 전용이다.</b>
 *
 * <p>추천 서버는 순간을 문자열 {@code externalId} 로만 가리킨다. 그것이 색인과 이
 * 응답을 잇는 유일한 열쇠이므로 <b>반드시 싣는다</b> — 숫자
 * {@code musicSectionAnalysisId} 는 적재 도구가 쓰라고 함께 주는 값이지 서빙이
 * 결합에 쓰는 값이 아니다.
 *
 * <p>{@code startTimeMs}·{@code endTimeMs} 는 <b>정수 ms</b> 다. 추천 서버 안쪽은
 * 초를 쓰므로 받는 쪽에서 한 번만 나눈다.
 *
 * <p>목록 두 개는 <b>비어 있을 수 있고, 그것은 정상이다</b> — 악기가 안 붙은 구간과
 * 순간 무드가 없는 구간이 실제로 있다. {@code null} 을 보내지 않는 이유는 받는 쪽이
 * "없다" 와 "안 왔다" 를 가르려다 조용히 빈 축을 만들지 않게 하려는 것이다.
 */
public record AiCatalogSectionResponse(
    String externalId,
    Integer musicSectionAnalysisId,
    String sectionLabel,
    Integer index,
    Integer startTimeMs,
    Integer endTimeMs,
    List<String> instruments,
    List<String> moods
) {
}
