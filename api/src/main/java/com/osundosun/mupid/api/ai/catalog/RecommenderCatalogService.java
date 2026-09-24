package com.osundosun.mupid.api.ai.catalog;

/**
 * 추천 후보를 MySQL 에서 모은다. 추천을 부르는 쪽(이어 듣기·오늘의 모먼트·닮은 구간·
 * 대화 추천)이 이것으로 요청 본문의 {@code catalog} 를 만든다.
 */
public interface RecommenderCatalogService {

    /**
     * 지금의 후보. 잠깐 캐시한다 — 추천이 불릴 때마다 표 여섯 개를 다시 읽을 이유가 없고,
     * 곡은 분석 배치가 드물게 바꾼다. 캐시가 낡는 동안 새 곡이 추천에 늦게 나올 뿐, 지워진
     * 곡은 응답을 붙이는 자리에서 한 번 더 걸러진다(계약 A).
     */
    RecommenderCatalog current();

}
