package com.osundosun.mupid.api.ai.dto;

/**
 * 코드표 한 줄. {@code ownerId} 는 곡 번호이기도 하고 구간 번호이기도 하다.
 *
 * <p>장르·무드·악기·순간 무드가 전부 "무엇인가에 코드 이름 하나가 달린다" 는 같은
 * 모양이라 표마다 record 를 따로 두지 않는다. 표를 가르는 것은 부르는 질의이지
 * 담는 그릇이 아니다.
 *
 * <p>{@code codeName} 만 싣고 {@code codeId} 는 싣지 않는다. 추천 서버가 쓰는 것은
 * 이름뿐이고(카탈로그 어휘가 곧 코드 이름이다), 번호를 함께 보내면 AI 쪽에
 * "번호로도 고를 수 있다" 는 두 번째 길이 생겨 어휘가 둘로 갈린다.
 */
public record AiCatalogCodeRow(Integer ownerId, String codeName) {
}
