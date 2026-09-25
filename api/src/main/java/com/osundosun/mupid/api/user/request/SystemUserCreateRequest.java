package com.osundosun.mupid.api.user.request;

import com.osundosun.mupid.api.auth.provider.JwtProvider;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemUserCreateRequest(

    /**
     * 배치 계정 이메일. 로그인 식별자로만 쓰고 메일을 보내지는 않는다.
     */
    @NotBlank(message = "이메일이 입력되지 않았습니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email,

    /**
     * 표시용 이름. 비우면 이메일 앞부분을 쓴다 (10자에서 자른다).
     *
     * <p>{@code users.nickname} 이 VARCHAR(10) 이다. 검사가 없으면 긴 이름은 INSERT 에서
     * {@code Data too long} 으로 500 이 된다.
     */
    @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
    String nickname,

    /**
     * 발급할 리프레시 토큰의 만료(일). 비우면 기본 7일, 180일까지 (S15P21A204-454).
     *
     * <p>ROLE_SYSTEM 은 회전하지 않으니 만료가 곧 토큰을 갈아 끼우는 주기다. 여기서 범위를 먼저
     * 보는 것은 계정을 만든 뒤에 거절하면 토큰 없는 계정이 남기 때문이다.
     */
    @Min(value = 1, message = "리프레시 토큰 만료는 1일 이상 180일 이하로 지정해야 합니다.")
    @Max(value = JwtProvider.MAX_SYSTEM_REFRESH_TOKEN_VALIDITY_DAYS,
        message = "리프레시 토큰 만료는 1일 이상 180일 이하로 지정해야 합니다.")
    Integer refreshTokenValidityDays

) {

    public SystemUserCreateRequest(String email, String nickname) {
        this(email, nickname, null);
    }
    
}
