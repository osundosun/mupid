package com.osundosun.mupid.api.music.dto;

import java.time.LocalDateTime;

/**
 * 곡의 한 시점에 달린 댓글.
 *
 * `mine` 은 서버가 판정한다. 화면이 `user_id` 를 비교하게 두면 로그인 정보가
 * 화면마다 필요해지고, 비교를 빠뜨리면 남의 댓글에 수정 버튼이 뜬다.
 */
public record MusicCommentSummary(
    Integer commentId,
    Integer musicId,
    Integer timestampMs,
    String content,
    Integer userId,
    String nickname,
    boolean mine,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {

}
