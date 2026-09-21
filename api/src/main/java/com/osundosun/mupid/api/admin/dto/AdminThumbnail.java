package com.osundosun.mupid.api.admin.dto;

/**
 * 행의 FK 자리에 그리는 **작은 이미지** 하나.
 *
 * <p>주소만 주고 화면이 알아서 받아 가게 하지 않는 이유는 **인증 여부가 파일마다 다르기**
 * 때문이다. 같은 {@code /api/v1/storage/image/{fileId}} 라도 앨범·아티스트 표지는 공개고
 * (목록의 표지를 {@code <img src>} 로 그리려고 열어 두었다), 프로필 사진은 아니다
 * ({@code ImageAccessPolicy.OWNER} — 남의 사진은 개인 정보라 토큰이 있어야 한다).
 *
 * <p>화면은 {@code <img src>} 로 {@code Authorization} 헤더를 붙일 수 없다. 그래서
 * {@code requiresAuthorization} 이 참이면 화면이 토큰을 붙여 {@code fetch} 로 받아
 * blob 주소로 그린다. 이 판단을 화면에 맡기면 카테고리 이름을 화면이 알아야 하고,
 * 서버가 정책을 바꾸면 조용히 깨진다 — 그래서 서버가 값으로 내려보낸다.
 *
 * @param url                    이미지 조회 주소 (`/api/v1/storage/image/{fileId}`)
 * @param requiresAuthorization  토큰을 붙여야 받아지는가 (비공개 카테고리)
 */
public record AdminThumbnail(
    String url,
    boolean requiresAuthorization
) {
}
