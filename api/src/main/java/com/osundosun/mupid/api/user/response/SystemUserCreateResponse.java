package com.osundosun.mupid.api.user.response;

import java.time.Instant;

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
    String refreshToken,

    /**
     * 리프레시 토큰 만료 시각. ROLE_SYSTEM 은 회전하지 않으므로 이 시각 전에 새 토큰으로 갈아 끼운다.
     */
    Instant refreshTokenExpiresAt

) {
    
}
