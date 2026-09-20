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
     *
     * <p><b>`COMMENT_GONE`(410) 과 나눠 쓴다.</b> 이 코드는 <b>그런 번호의 댓글이
     * 아예 없을 때</b>만 나간다. 삭제된 댓글이면 `COMMENT_GONE` 이다 —
     * `COMMENT_NOT_FOUND` 라는 이름과 달리 예전에는 둘을 구분하지 않았다.
     */
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 댓글을 찾을 수 없습니다."),

    /**
     * 요청한 댓글이 <b>삭제</b>되었습니다. <b>410</b> 이다.
     *
     * <p>댓글 삭제는 `is_deleted` 를 세우는 소프트 삭제다
     * ([decision/0013](../../knowledge/decision/0013-soft-delete-policy.md)). 조회가
     * `findByMusicCommentIdAndIsDeletedFalse` 로 거르므로 그대로는 "없다" 와
     * 구분되지 않는다 — `findByMusicCommentId` 로 행 자체를 한 번 더 본다.
     *
     * <p>위 `COMMENT_NOT_FOUND` 는 <b>없는 댓글</b>에 남는다. 그쪽은 댓글 번호를
     * 잘못 준 것이라 404 가 맞다.
     */
    COMMENT_GONE(HttpStatus.GONE, "요청한 댓글은 삭제되었습니다."),

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
