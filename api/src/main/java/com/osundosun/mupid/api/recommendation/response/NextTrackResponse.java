package com.osundosun.mupid.api.recommendation.response;

import java.util.List;

/**
 * 이어 듣기 응답.
 *
 * <p><b>빈 목록은 오류가 아니다.</b> 조건에 맞는 곡이 없으면 `tracks` 가 비고
 * `shortfall` 이 찬다. 억지로 채우면 조건과 무관한 곡이 나가고, 그게 더 나쁘다.
 */
public record NextTrackResponse(

    /**
     * 이 추천 묶음의 번호. 화면이 재생 이벤트에 되돌려 싣는다(`rec_id`).
     *
     * <p>저장되지 않는다 — 자동재생은 수시로 불리므로 한 벌씩 영속할 이유가 없다.
     * 재생과 추천을 이어 붙이는 상관 키다.
     */
    long recommendationId,

    List<NextTrack> tracks,

    /** 요청한 수를 못 채운 만큼. 임의로 채우지 않는다는 뜻이다. */
    int shortfall

) {

}
