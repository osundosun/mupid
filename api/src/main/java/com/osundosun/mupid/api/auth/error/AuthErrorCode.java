package com.osundosun.mupid.api.auth.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {
 
    /**
     * 올바르지 않은 인증 토큰을 사용하였습니다.
     */
    InvalidAuthToken(HttpStatus.UNAUTHORIZED, "MUPID_INVALID_AUTH_TOKEN", "올바르지 않은 인증 토큰을 사용하였습니다."),
    
    /**
     * 이메일 혹은 패스워드가 잘못되었습니다.
     */
    WrongEmailOrPassword(HttpStatus.UNAUTHORIZED, "MUPID_WRONG_EMAIL_OR_PASSWORD", "이메일 혹은 패스워드가 잘못되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

}
