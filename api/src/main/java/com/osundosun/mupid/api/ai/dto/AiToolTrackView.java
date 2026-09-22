package com.osundosun.mupid.api.ai.dto;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 추천 결과를 **모델에게** 돌려줄 때의 모양 (S15P21A204-426).
 *
 * <h2>왜 화면에 나가는 것과 따로 두는가</h2>
 *
 * 둘은 받는 사람이 다르다. 화면은 줄을 그리고 재생까지 해야 해서 곡 표현이 통째로
 * 필요하지만({@link AiRecommendedTrack} 의 {@code music}), 모델은 <b>곡 이야기를 하고
 * 없는 곡을 지어내지 않기 위한</b> 최소한만 있으면 된다.
 *
 * <p>그 둘을 한 DTO 로 겸하다가 대화가 통째로 502 가 났다. {@code MusicSummary} 에는
 * {@code LocalDateTime releaseDate} 가 있는데, 게이트웨이 전용 매퍼
 * ({@link com.osundosun.mupid.api.ai.client.AiJson})에는 JavaTimeModule 이 없다 —
 * 그 매퍼는 필드명이 OpenAI 와의 계약이라 애플리케이션 ObjectMapper 와 일부러 떼어 둔
 * 것이다. 직렬화가 {@code InvalidDefinitionException} 으로 터지고, {@code writeString}
 * 이 그것을 {@code AI_REQUEST_FAILED} 로 바꿔 **추천이 성공한 모든 턴이 502** 가 됐다.
 *
 * <p>매퍼에 모듈을 더해 막을 수도 있었다. 그러지 않은 이유는, 그렇게 고치면 재생 횟수·
 * 좋아요 수·표지 주소·발매일처럼 <b>모델이 쓸 수 없는 값</b>이 매 턴 프롬프트로 나가는
 * 상태는 그대로이기 때문이다. 보낼 이유가 없는 것을 안 보내는 것이 원인에 더 가깝다.
 *
 * <p>표지 주소를 뺀 것 말고는 424 이전에 모델이 보던 모양 그대로다. 모델이 보는 것을
 * 바꾸면 답변이 바뀌므로, 고치는 김에 더 손대지 않는다.
 */
public record AiToolTrackView(

    int rankOrder,

    int musicId,

    String title,

    String artist,

    String album,

    Integer musicSectionId,

    SectionLabel sectionLabel,

    int startTimeMs,

    int endTimeMs,

    double score,

    String reasonCode,

    String reason

) {

    public static AiToolTrackView of(AiRecommendedTrack track) {
        return new AiToolTrackView(
            track.rankOrder(),
            track.musicId(),
            track.music().title(),
            track.music().artist().name(),
            track.music().album().title(),
            track.musicSectionId(),
            track.sectionLabel(),
            track.startTimeMs(),
            track.endTimeMs(),
            track.score(),
            track.reasonCode(),
            track.reason());
    }
}
