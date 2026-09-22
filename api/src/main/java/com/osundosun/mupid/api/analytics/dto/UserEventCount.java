package com.osundosun.mupid.api.analytics.dto;

/**
 * 이벤트 종류 하나의 건수 (S15P21A204-430).
 *
 * <p>하루치 목록에는 상한이 있어서 <b>받은 행 수만으로는 전체를 알 수 없다.</b> 이 값이
 * 그 전체다 — 둘을 비교해 밴드가 잘렸는지 판단한다. 조용히 잘린 밴드는 "이 사람은 그날
 * 그것밖에 안 했다" 는 거짓말이 된다.
 *
 * @param eventType 이벤트 종류. 아는 22종 밖의 값이 올 수 있다 — 적재는 되고 해석만 없다
 * @param events    그날 그 종류의 건수. {@code PLAY_PROGRESS} 도 여기에는 들어 있다
 */
public record UserEventCount(
    String eventType,
    long events
) {

}
