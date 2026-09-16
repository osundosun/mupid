package com.osundosun.mupid.api.recommendation.response;

import java.util.List;

/**
 * 닮은 구간 응답.
 *
 * <p>추천과 다르다 — 개인 취향을 섞지 않으므로 `recommendationId` 가 없고, 같은
 * 질문에 늘 같은 답이라 캐시할 수 있다.
 */
public record SimilarSectionResponse(

    /** 기준이 된 구간. 요청 값을 그대로 돌려준다. */
    Integer musicSectionId,

    List<SimilarSection> tracks,

    int shortfall,

    /** 임베딩 판. 바뀌면 닮은 구간 결과가 달라진다. */
    String embeddingVersion

) {

}
