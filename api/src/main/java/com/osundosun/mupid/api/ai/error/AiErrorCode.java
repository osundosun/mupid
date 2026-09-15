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
    AI_TOOL_ROUND_EXCEEDED(HttpStatus.BAD_GATEWAY, "AI 가 도구 호출을 끝내지 못했습니다."),

    /**
     * 추천 서버 주소가 설정되지 않았습니다.
     *
     * <p>대화는 되지만 곡은 고를 수 없다. 없는 곡을 지어내지 않으려고 실패로 답한다.
     */
    RECOMMENDER_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "곡 추천 기능이 설정되지 않았습니다."),

    /**
     * 추천 서버가 응답하지 않습니다. 재시도해 볼 만한 실패다 (500·503·타임아웃).
     */
    RECOMMENDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "곡 추천 서버가 응답하지 않습니다."),

    /**
     * 추천 요청이 거부되었습니다. 다시 불러도 같은 답이 온다 (400·501 등).
     */
    RECOMMENDER_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "곡 추천 요청이 처리되지 않았습니다.");

    private final HttpStatus status;
    private final String message;

}
