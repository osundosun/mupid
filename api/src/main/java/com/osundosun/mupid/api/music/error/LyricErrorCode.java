package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LyricErrorCode implements ErrorCode {
    
    /**
     * 요청한 가사를 찾을 수 없습니다.
     */
    LYRIC_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 가사를 찾을 수 없습니다."),

    /**
     * 올바르지 않은 가사 시간이 입력되었습니다.
     */
    LYRIC_INVALID_TIME_MS(HttpStatus.BAD_REQUEST, "올바르지 않은 가사 시간이 입력되었습니다.");

    private final HttpStatus status;
    private final String message;

}
