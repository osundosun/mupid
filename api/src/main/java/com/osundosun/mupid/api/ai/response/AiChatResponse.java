package com.osundosun.mupid.api.ai.response;

import java.util.List;

import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.ai.dto.AiToolCallRecord;

/**
 * 화면(`frontend/components/mupid/chat.tsx`)이 말풍선 하나를 그리는 데 필요한 전부.
 * 본문은 `reply`, 말풍선 아래 곡 목록은 `tracks` 다.
 */
public record AiChatResponse(

    String reply,

    /**
     * 이 답변이 남긴 대화 세션의 번호. 화면은 다음 턴에 이 값을 `sessionId` 로 되돌려
     * 보낸다 — 그래야 한 대화가 한 세션으로 이어진다.
     *
     * <p><b>기록을 저장하지 못했으면 `null` 이다.</b> 답변 자체는 정상이므로 화면은
     * 대화를 이어 가면 되고, 세션 번호가 없으면 서버가 다음 턴에 새 세션을 연다.
     */
    Integer sessionId,

    /**
     * 이 답변이 만든 추천 묶음의 번호. 추천이 없었으면 `null` 이다.
     *
     * <p>화면이 재생 이벤트에 그대로 되돌려 실어 보낸다(`rec_id`). 그래야 "이 추천으로
     * 튼 곡을 끝까지 들었는가" 를 ClickHouse 에서 물어볼 수 있다. 이 값이 없으면 재생과
     * 추천이 이어지지 않아 추천 성과를 잴 수 없다.
     *
     * <p>아직 `daily_recommendations` 에 저장되지는 않는다 — 지금은 한 응답 안의 곡들을
     * 묶는 상관 키다.
     */
    Long recommendationId,

    /**
     * 추천 툴이 돌아갔을 때만 채워진다. 그냥 대화만 했으면 빈 배열이다 (null 이 아니다).
     */
    List<AiRecommendedTrack> tracks,

    /**
     * 이 턴에 일어난 **툴 호출 기록**. 호출 순서대로 전부 담는다 (S15P21A204-409).
     *
     * <p>그냥 대화만 한 턴이면 빈 배열이다(null 이 아니다). 화면은 **비어 있으면 아무것도
     * 그리지 않는다** — 툴 호출이 없던 턴에 "도구 호출" 줄을 띄우면 거짓말이 된다.
     *
     * <p>곡 목록({@link #tracks})과 달리 **접지 않는다.** 실제로 실행한 호출을 전부 담는다.
     * 다만 추천({@code recommend_music})은 요청 한 번에 한 번만 실행하므로 그 기록은 많아야
     * 한 줄이다 — 모델이 다시 부른 것은 실행하지 않고 기록에도 없다 (S15P21A204-437).
     *
     * <p>실패한 호출도 담는다. 예전에는 {@code AiChatServiceImpl.runTool} 이
     * {@code RECOMMENDER_UNAVAILABLE} 을 삼키고 대화를 이어 가서, 사용자에게는
     * "추천이 안 온다" 로만 보였다. 이제 화면에 실패가 보인다.
     */
    List<AiToolCallRecord> toolCalls

) {

}
