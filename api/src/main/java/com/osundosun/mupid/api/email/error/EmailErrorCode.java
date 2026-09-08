package com.osundosun.mupid.api.email.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EmailErrorCode implements ErrorCode {

    /**
     * 해당 이메일 인증은 유효하지 않습니다.
     */
    INVALID_VERIFICATION_ID(HttpStatus.NOT_FOUND, "해당 이메일 인증은 유효하지 않습니다."),

    /**
     * 인증 시간이 초과하였습니다.
     */
    VERIFICATION_HAS_EXPIRED(HttpStatus.BAD_REQUEST, "인증 시간이 초과하였습니다."),
    
    /**
     * 인증 토큰이 일치하지 않습니다.
     */
    INVALID_VERIFICATION_TOKEN(HttpStatus.UNAUTHORIZED, "인증 토큰이 일치하지 않습니다."),
    
    /**
     * 이미 인증된 토큰입니다.
     */
    ALREADY_VERIFIED_TOKEN(HttpStatus.BAD_REQUEST, "이미 인증된 토큰입니다.");
    
    private final HttpStatus status;
    private final String message;

}
