package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 곡의 한 시점에 댓글을 단다.
 *
 * `content` 상한 100자는 `music_comments.content` 가 varchar(100) 이라서다.
 * 화면도 같은 값을 쓴다.
 */
public record MusicCommentCreateRequest(

    @NotNull(message = "댓글을 달 시점이 입력되지 않았습니다.")
    @Min(value = 0, message = "시점은 0 이상이어야 합니다.")
    @Max(value = MusicCommentCreateRequest.TIMESTAMP_MAX, message = "시점이 너무 큽니다.")
    Integer timestampMs,

    @NotBlank(message = "댓글 내용이 입력되지 않았습니다.")
    @Size(max = 100, message = "댓글은 100자를 넘을 수 없습니다.")
    String content

) {

    /** `timestamp_ms` 가 int unsigned 다. int 로 받으므로 양수 범위가 상한이다. */
    public static final int TIMESTAMP_MAX = Integer.MAX_VALUE;

}
