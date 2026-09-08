package com.osundosun.mupid.api.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {
    
    /**
     * Endpoint가 존재하지 않습니다.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Endpoint가 존재하지 않습니다."),

    /**
     * 요청 데이터 파싱에 실패했습니다.
     */
    NOT_READABLE_MESSAGE(HttpStatus.NOT_FOUND, "요청 데이터 파싱에 실패했습니다."),

    /**
     * 데이터가 잘못 입력되었습니다.
     */
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "데이터가 잘못 입력되었습니다."),

    /**
     * 서버 내부 오류가 발생했습니다.
     */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

}
