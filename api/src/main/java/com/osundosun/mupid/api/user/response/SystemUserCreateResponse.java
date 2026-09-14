package com.osundosun.mupid.api.user.response;

public record SystemUserCreateResponse(

    int userId,

    String email,

    /**
     * 자동 생성된 비밀번호. 생성 응답에서만 평문으로 나온다.
     */
    String password,

    /**
     * 배치 프로그램이 보관할 리프레시 토큰.
     * 이 토큰으로 `GET /api/v1/auth` 를 호출해 액세스 토큰을 재발급받는다.
     */
    String refreshToken

) {
    
}
