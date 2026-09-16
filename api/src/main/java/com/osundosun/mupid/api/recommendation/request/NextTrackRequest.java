package com.osundosun.mupid.api.recommendation.request;

import java.util.List;

import jakarta.validation.constraints.Positive;

/**
 * 이어 듣기 요청.
 *
 * <p><b>`user_id` 를 받지 않는다.</b> 토큰에서 꺼낸다 — 본문으로 받으면 남의 추천을
 * 받아 갈 수 있다.
 *
 * @param currentMusicId        지금 듣던 곡. 흐름 보정에 쓴다
 * @param currentMusicSectionId 지금 듣던 구간
 * @param excludeMusicIds       이번 목록에서 뺄 곡. 지금 듣던 곡은 서버가 알아서 뺀다
 * @param limit                 기본 5, 최대 50
 */
public record NextTrackRequest(

    @Positive(message = "올바르지 않은 음악 번호입니다.")
    Integer currentMusicId,

    @Positive(message = "올바르지 않은 구간 번호입니다.")
    Integer currentMusicSectionId,

    List<@Positive Integer> excludeMusicIds,

    @Positive(message = "추천 개수는 1 이상이어야 합니다.")
    Integer limit

) {

}
