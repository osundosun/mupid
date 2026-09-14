package com.osundosun.mupid.api.recommendation.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 추천.
 *
 * 추천 파이프라인이 아직 없어서 대부분 `groups` 가 비어 있다. **404 가 아니다** —
 * 사람은 있는데 추천만 없는 것은 오류가 아니다.
 */
public record DailyRecommendationResponse(

    /** 실제로 돌려준 추천의 기준일. 요청한 날짜와 다를 수 있다. */
    LocalDate date,

    List<DailyRecommendationGroup> groups

) {

}
