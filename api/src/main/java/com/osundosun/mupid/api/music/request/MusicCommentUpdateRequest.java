package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 댓글 수정.
 *
 * 시점은 바꾸지 않는다. "이 순간에 남긴 말" 이 댓글의 정체라, 시점이 바뀌면
 * 다른 댓글이다. 옮기고 싶으면 지우고 다시 단다.
 */
public record MusicCommentUpdateRequest(

    @NotBlank(message = "댓글 내용이 입력되지 않았습니다.")
    @Size(max = 100, message = "댓글은 100자를 넘을 수 없습니다.")
    String content

) {

}
