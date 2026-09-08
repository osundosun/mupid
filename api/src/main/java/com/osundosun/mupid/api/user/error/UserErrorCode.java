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
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),

    /**
     * 이메일이 중복되었습니다.
     */
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "이메일이 중복되었습니다."),
    
    /**
     * 요청한 유저를 찾을 수 없습니다.
     */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 유저를 찾을 수 없습니다.");

    
    private final HttpStatus status;
    private final String message;

}
