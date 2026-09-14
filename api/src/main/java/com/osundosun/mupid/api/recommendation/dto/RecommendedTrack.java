package com.osundosun.mupid.api.recommendation.dto;

import com.osundosun.mupid.api.music.dto.MusicSummary;

/**
 * 추천 묶음 안의 곡 하나.
 *
 * 곡 표현은 `곡 목록 조회` 와 같다. 추천이 덧붙이는 것은 순위와 사유뿐이라
 * 곡을 감싸는 모양으로 둔다 — 곡 표현에 추천 전용 필드를 섞으면 목록·상세·
 * 좋아요에서 늘 비어 있는 칸이 생긴다.
 */
public record RecommendedTrack(

    /** 묶음 안에서의 순위. */
    Integer rank,

    /** 이 곡이 뽑힌 이유. */
    String reason,

    MusicSummary music

) {

}
