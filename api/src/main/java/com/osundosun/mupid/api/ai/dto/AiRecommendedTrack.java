package com.osundosun.mupid.api.ai.dto;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 툴이 돌려주는 추천 곡 하나.
 *
 * 필드는 **추천 서버의 `/internal/recommendations/*` 계약**(`ai_server/app/internal_api.py`
 * 의 `RecommendationItem`)을 그대로 따른다. 추천 서버 응답을 그대로 옮겨 담는다.
 *
 * 추천 단위가 곡이 아니라 **구간**인 이유는
 * `api/knowledge/decision/0006-section-as-recommendation-unit.md` 에 있다.
 *
 * `title`·`artist`·`album`·`imageUrl` 은 추천 서버가 주지 않는 값이다. 숫자 ID 만 주고받는
 * 것이 계약이라, `musicId` 로 MySQL 에서 채운다 (`AiRecommendToolImpl`).
 */
public record AiRecommendedTrack(

    /** 추천 묶음 안의 순위. 1부터. */
    int rankOrder,

    int musicId,

    String title,

    String artist,

    String album,

    String imageUrl,

    /** 추천된 구간. 곡 전체가 아니라 이 구간부터 들려준다. */
    Integer musicSectionId,

    SectionLabel sectionLabel,

    int startTimeMs,

    int endTimeMs,

    /** 추천 점수. 화면에는 "일치율" 로 보인다. */
    double score,

    /**
     * 사유 분류. 추천 서버가 정한다.
     *
     * `SIMILAR_PREFERRED_MOMENT` 청취 이력에서 만든 선호 구간과 닮았다
     * `ONBOARDING_MATCH`         이력이 없어 온보딩 답변으로 뽑았다
     * `QUERY_SEMANTIC_MATCH`     조건 검색 + 의미 유사도
     * `QUERY_FILTER_MATCH`       조건 검색 (필터만)
     * `EXPLORE_NEW`              탐색 슬롯
     * `COLD_START`               근거가 없다
     *
     * **늘 수 있다.** 모르는 값에서 실패하지 말 것 — 화면은 `reason` 문장을 그대로 쓰면 된다.
     */
    String reasonCode,

    /** 사람이 읽는 추천 이유. 화면에 그대로 나간다. */
    String reason

) {

}
