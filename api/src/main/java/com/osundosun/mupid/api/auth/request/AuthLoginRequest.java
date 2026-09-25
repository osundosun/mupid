package com.osundosun.mupid.api.auth.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record AuthLoginRequest(

    @Email(message = "이메일 형식이 잘못되었습니다.")
    @NotNull(message = "이메일이 입력되지 않았습니다.")
    String email,

    @NotNull(message = "패스워드가 입력되지 않았습니다.")
    String password,

    /**
     * 리프레시 토큰 만료(일). **ROLE_SYSTEM 만** 지정할 수 있고 180일까지다. 비우면 기본 7일.
     *
     * <p>범위 검사는 역할을 안 뒤에 한다({@code JwtProvider.resolveRefreshTokenValidity}) —
     * 사람 계정이 보내면 범위와 상관없이 거절해야 해서 여기 애너테이션으로 두지 않는다.
     */
    Integer refreshTokenValidityDays

) {

    public AuthLoginRequest(String email, String password) {
        this(email, password, null);
    }
    
}
