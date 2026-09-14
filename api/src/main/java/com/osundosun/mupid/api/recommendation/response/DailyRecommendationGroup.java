package com.osundosun.mupid.api.recommendation.response;

import java.util.List;

import com.osundosun.mupid.api.recommendation.dto.RecommendedTrack;

/** 추천 묶음 하나. 홈 화면의 "오늘의 Mupid Mix" 카드 한 장이다. */
public record DailyRecommendationGroup(

    Integer recommendationId,

    String title,

    /** 이 묶음이 만들어진 이유. */
    String reason,

    /** 묶음의 분위기(코드 이름). */
    String mood,

    /**
     * 파이프라인이 적어 둔 수록곡 수.
     * `tracks` 길이와 다를 수 있다 — 추천이 만들어진 뒤 지워진 곡은 빠진다.
     */
    Integer musicCount,

    List<RecommendedTrack> tracks

) {

}
