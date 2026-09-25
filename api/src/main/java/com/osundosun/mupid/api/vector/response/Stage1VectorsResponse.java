package com.osundosun.mupid.api.vector.response;

/**
 * 모델 재계산 한 묶음의 결과.
 *
 * @param version     모델 버전 ({@code v3})
 * @param vector      벡터 이름 ({@code stage1_v3})
 * @param updated     덧붙인 구간 수
 * @param vectorAdded 이 요청이 컬렉션에 그 벡터 이름을 처음 만들었는가
 */
public record Stage1VectorsResponse(
    String version,
    String vector,
    int updated,
    boolean vectorAdded
) {
}
