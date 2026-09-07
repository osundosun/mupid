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
    VerificationIdNotFound(HttpStatus.NOT_FOUND, "MUPID_VERIFICATION_ID_NOT_FOUND", "해당 이메일 인증은 유효하지 않습니다."),

    /**
     * 인증 시간이 초과하였습니다.
     */
    VerificationHasExpired(HttpStatus.BAD_REQUEST, "MUPID_VERIFICATION_HAS_EXPIRED", "인증 시간이 초과하였습니다."),
    
    /**
     * 인증 토큰이 일치하지 않습니다.
     */
    VerificationInvalidToken(HttpStatus.UNAUTHORIZED, "MUPID_VERIFICATION_INVALID_TOKEN", "인증 토큰이 일치하지 않습니다.");
    
    private final HttpStatus status;
    private final String code;
    private final String message;

}
