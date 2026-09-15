package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MusicCommentErrorCode implements ErrorCode {

    /**
     * 없거나 이미 삭제된 댓글.
     */
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 댓글을 찾을 수 없습니다."),

    /**
     * 남의 댓글을 고치거나 지우려 할 때.
     */
    COMMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "본인이 작성한 댓글만 수정·삭제할 수 있습니다."),

    /**
     * 곡 길이를 벗어난 시점. 곡 길이를 모르면(스트림 없음) 검사하지 않는다.
     */
    COMMENT_TIMESTAMP_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "곡 길이를 벗어난 시점입니다.");

    private final HttpStatus status;
    private final String message;

}
