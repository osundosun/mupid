package com.osundosun.mupid.api.ai.catalog;

/**
 * 추천 후보를 MySQL 에서 모은다. 대화 추천의 조건 어휘({@link RecommenderFacets})가 쓴다.
 *
 * <p>추천 요청 본문에는 더 이상 싣지 않는다(계약 v3, S15P21A204-458) — 추천 서버가 같은 값을
 * ClickHouse 피처 저장소에서 읽는다.
 */
public interface RecommenderCatalogService {

    /**
     * 지금의 후보. 잠깐 캐시한다 — 툴 선언(대화 한 턴)마다 표 여섯 개를 다시 읽을 이유가 없고,
     * 곡은 분석 배치가 드물게 바꾼다. 캐시가 낡는 동안 새 어휘가 늦게 보일 뿐이다.
     */
    RecommenderCatalog current();

}
