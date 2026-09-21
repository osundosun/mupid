package com.osundosun.mupid.api.recommendation.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 오늘의 추천.
 *
 * `groups` 가 비어 있어도 **404 가 아니다** — 사람은 있는데 추천만 없는 것은 오류가
 * 아니다. 다만 저장된 추천이 하나도 없는 사람에게는 서버가 그 자리에서 곡을 골라
 * 채운다(`DailyRecommendationServiceImpl.coldStart`). 그래서 비는 경우는 카탈로그가
 * 통째로 비었을 때뿐이다.
 */
public record DailyRecommendationResponse(

    /** 실제로 돌려준 추천의 기준일. 요청한 날짜와 다를 수 있다. */
    LocalDate date,

    List<DailyRecommendationGroup> groups

) {

}
