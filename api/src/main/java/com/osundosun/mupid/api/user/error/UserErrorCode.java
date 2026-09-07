package com.osundosun.mupid.api.user.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    
    /**
     * 이메일 인증이 필요합니다.
     */
    EmailVerificationRequired(HttpStatus.BAD_REQUEST, "MUPUD_EMAIL_VERIFICATION_REQUIRED", "이메일 인증이 필요합니다."),
    
    /**
     * 요청한 유저를 찾을 수 없습니다.
     */
    UserNotFound(HttpStatus.NOT_FOUND, "MUPID_USER_NOT_FOUND", "요청한 유저를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

}
