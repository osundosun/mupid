package com.osundosun.mupid.api.event.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventErrorCode implements ErrorCode {

    /**
     * 한 번에 보낼 수 있는 이벤트 수를 넘었습니다.
     */
    EVENT_BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "한 번에 보낼 수 있는 이벤트 수를 넘었습니다."),

    /**
     * 이벤트를 발행하지 못했습니다.
     */
    EVENT_PUBLISH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "이벤트를 발행하지 못했습니다.");

    private final HttpStatus status;
    private final String message;

}
