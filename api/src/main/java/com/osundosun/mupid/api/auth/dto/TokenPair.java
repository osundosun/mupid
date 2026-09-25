package com.osundosun.mupid.api.auth.dto;

import java.time.Instant;

public record TokenPair(

    String accessToken,

    String refreshToken,

    /**
     * 리프레시 토큰의 만료 시각. 쿠키 수명을 여기에 맞춘다.
     *
     * <p>ROLE_SYSTEM 은 회전하지 않아 같은 토큰이 돌아오므로, 쿠키를 늘 7일로 주면 토큰보다
     * 쿠키가 오래 산다(또는 긴 토큰의 쿠키가 먼저 사라진다).
     */
    Instant refreshTokenExpiresAt

) {
    
}
