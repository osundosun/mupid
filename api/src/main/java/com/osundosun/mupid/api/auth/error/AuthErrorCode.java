package com.osundosun.mupid.api.auth.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

    /**
     * 접근 권한이 없습니다.
     */
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
 
    /**
     * 인증 정보가 유효하지 않거나 존재하지 않습니다.
     */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않거나 존재하지 않습니다."),
    
    /**
     * 이메일 혹은 패스워드가 잘못되었습니다.
     */
    WRONG_EMAIL_OR_PASSWORD(HttpStatus.UNAUTHORIZED, "이메일 혹은 패스워드가 잘못되었습니다.");

    private final HttpStatus status;
    private final String message;

}
