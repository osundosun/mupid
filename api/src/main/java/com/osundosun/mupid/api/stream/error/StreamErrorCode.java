package com.osundosun.mupid.api.stream.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StreamErrorCode implements ErrorCode {
    
    /**
     * 요청한 스트림을 찾을 수 없습니다.
     */
    STREAM_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 스트림을 찾을 수 없습니다."),

    /**
     * 같은 코덱·비트레이트의 스트림이 이미 있습니다.
     */
    DUPLICATE_STREAM(HttpStatus.CONFLICT, "같은 코덱·비트레이트의 스트림이 이미 등록되어 있습니다."),

    /**
     * 청크가 다 올라오지 않았는데 완료 처리를 시도했습니다.
     */
    STREAM_CHUNK_INCOMPLETE(HttpStatus.CONFLICT, "청크가 모두 업로드되지 않아 완료 처리할 수 없습니다."),

    /**
     * 완료된 스트림에는 청크를 더 올릴 수 없습니다.
     */
    STREAM_ALREADY_DONE(HttpStatus.CONFLICT, "이미 완료된 스트림입니다.");

    private final HttpStatus status;
    private final String message;

}
