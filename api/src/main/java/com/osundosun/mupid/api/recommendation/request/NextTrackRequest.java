package com.osundosun.mupid.api.recommendation.request;

import java.util.List;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 이어 듣기 요청.
 *
 * <p><b>`user_id` 를 받지 않는다.</b> 토큰에서 꺼낸다 — 본문으로 받으면 남의 추천을
 * 받아 갈 수 있다.
 *
 * @param currentMusicId        지금 듣던 곡. 흐름 보정에 쓴다
 * @param currentMusicSectionId 지금 듣던 구간
 * @param excludeMusicIds       이번 목록에서 뺄 곡. 지금 듣던 곡은 서버가 알아서 뺀다.
 *                              최대 {@value #MAX_EXCLUDE}곡
 * @param limit                 기본 5, 최대 50
 */
public record NextTrackRequest(

    @Positive(message = "올바르지 않은 음악 번호입니다.")
    Integer currentMusicId,

    @Positive(message = "올바르지 않은 구간 번호입니다.")
    Integer currentMusicSectionId,

    /*
     * 추천 서버로 가는 본문의 크기를 이 목록 하나가 정한다(계약 v3 — `catalog` 가 빠진 뒤 나머지는
     * 수백 바이트다). 곡당 약 10B 라 500곡이면 약 5KB. 상한이 없으면 화면 버그 하나가 요청마다
     * 수만 곡을 실어 보내고, 곡마다 문자열 ID 로 옮기는 조회도 그만큼 돈다.
     */
    @Size(max = MAX_EXCLUDE, message = "뺄 곡은 최대 500곡까지 보낼 수 있습니다.")
    List<@Positive Integer> excludeMusicIds,

    @Positive(message = "추천 개수는 1 이상이어야 합니다.")
    Integer limit

) {

    /** 뺄 곡 수의 상한. 애너테이션이 상수를 요구해 여기 둔다. */
    public static final int MAX_EXCLUDE = 500;

}
