package com.osundosun.mupid.api.vector.response;

/**
 * 재계산 관문 — {@code stage1_v{version}} 이 전 구간에 채워졌는가 (S15P21A204-457, 설계 §6.3 ④-c).
 *
 * <p>CI {@code model-publish} 가 {@code complete} 만 본다. 나머지는 멈췄을 때 사람이 읽는 수다.
 *
 * @param name             모델 이름 ({@code stage1})
 * @param version          모델 버전
 * @param vector           벡터 이름 ({@code stage1_v3})
 * @param vectorExists     컬렉션에 그 벡터 이름이 있는가
 * @param expectedSections MySQL 의 살아 있는 곡의 구간 수 — 채워져야 할 수
 * @param points           Qdrant 에서 {@code mert} 를 가진 포인트 수 — AI 준비 판정의 분모
 * @param filled           그 벡터를 가진 포인트 수
 * @param complete         {@code filled ≥ expectedSections} 이고 {@code filled ≥ points} 인가. 뒤의 조건이
 *                         빠지면 관문은 통과했는데 새 파드가 준비 판정에서 멈춘다(AI 는 {@code mert} 수와 비교한다)
 */
public record Stage1StatusResponse(
    String name,
    String version,
    String vector,
    boolean vectorExists,
    long expectedSections,
    long points,
    long filled,
    boolean complete
) {
}
