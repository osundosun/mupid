package com.osundosun.mupid.api.user.request;

import com.osundosun.mupid.api.auth.provider.JwtProvider;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 관리자가 시스템 계정의 리프레시 토큰을 새로 발급한다 (S15P21A204-454).
 */
public record SystemUserTokenRequest(

    /** 만료(일). 비우면 기본 7일, 180일까지 */
    @Min(value = 1, message = "리프레시 토큰 만료는 1일 이상 180일 이하로 지정해야 합니다.")
    @Max(value = JwtProvider.MAX_SYSTEM_REFRESH_TOKEN_VALIDITY_DAYS,
        message = "리프레시 토큰 만료는 1일 이상 180일 이하로 지정해야 합니다.")
    Integer refreshTokenValidityDays

) {
    
}
