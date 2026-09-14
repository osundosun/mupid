package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.ReactionType;

import jakarta.validation.constraints.NotNull;

/**
 * 곡 반응 설정.
 *
 * 해제는 이 요청이 아니라 `DELETE` 로 한다. 반응 없음을 `null` 로 표현하면
 * "값을 빠뜨린 요청" 과 구분되지 않는다.
 */
public record MusicReactionRequest(

    @NotNull(message = "반응 유형을 입력해주세요.")
    ReactionType reactionType

) {

}
