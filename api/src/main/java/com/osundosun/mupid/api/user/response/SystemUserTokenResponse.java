package com.osundosun.mupid.api.user.response;

import java.time.Instant;

public record SystemUserTokenResponse(

    int userId,

    String email,

    /** 새 리프레시 토큰. 이 응답에서만 평문으로 나온다 */
    String refreshToken,

    Instant refreshTokenExpiresAt

) {
    
}
