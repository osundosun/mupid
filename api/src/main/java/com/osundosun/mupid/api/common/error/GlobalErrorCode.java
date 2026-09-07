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
    NotFoundError(HttpStatus.NOT_FOUND, "MUPID_ENDPOINT_NOT_FOUND", "Endpoint가 존재하지 않습니다."),

    /**
     * 요청에 필요한 데이터가 입력되지 않았습니다.
     */
    NoRequestBody(HttpStatus.NOT_FOUND, "MUPID_NO_REQUEST_BODY", "요청에 필요한 데이터가 입력되지 않았습니다."),

    /**
     * 서버 내부 오류가 발생했습니다.
     */
    InternalServerError(HttpStatus.INTERNAL_SERVER_ERROR, "MUPID_SERVER_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

}
