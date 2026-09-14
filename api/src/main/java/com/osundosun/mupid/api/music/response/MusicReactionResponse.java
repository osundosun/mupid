package com.osundosun.mupid.api.music.response;

import java.util.List;

/**
 * 이 사람이 반응한 곡 번호.
 *
 * 화면이 목록 어디서든 하트 상태를 그리려면 전부 필요해서 페이징하지 않는다.
 * 번호만이라 곡을 수천 개 좋아해도 응답이 가볍다. 곡 정보까지 필요한 화면은
 * `GET /api/v1/user/reaction/music` 을 쓴다.
 */
public record MusicReactionResponse(
    List<Integer> like,
    List<Integer> dislike
) {

}
