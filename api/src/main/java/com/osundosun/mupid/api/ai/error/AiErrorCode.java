package com.osundosun.mupid.api.ai.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AiErrorCode implements ErrorCode {

    /**
     * AI 대화 기능이 설정되지 않았습니다.
     */
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "AI 대화 기능이 설정되지 않았습니다."),

    /**
     * AI 응답을 받지 못했습니다.
     */
    AI_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "AI 응답을 받지 못했습니다."),

    /**
     * AI 가 빈 응답을 보냈습니다.
     */
    AI_EMPTY_RESPONSE(HttpStatus.BAD_GATEWAY, "AI 가 빈 응답을 보냈습니다."),

    /**
     * AI 가 도구 호출을 끝내지 못했습니다.
     */
    AI_TOOL_ROUND_EXCEEDED(HttpStatus.BAD_GATEWAY, "AI 가 도구 호출을 끝내지 못했습니다.");

    private final HttpStatus status;
    private final String message;

}
