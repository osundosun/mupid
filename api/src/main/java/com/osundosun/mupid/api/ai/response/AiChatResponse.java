package com.osundosun.mupid.api.ai.response;

import java.util.List;

import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;

/**
 * 화면(`frontend/components/mupid/chat.tsx`)이 말풍선 하나를 그리는 데 필요한 전부.
 * 본문은 `reply`, 말풍선 아래 곡 목록은 `tracks` 다.
 */
public record AiChatResponse(

    String reply,

    /**
     * 추천 툴이 돌아갔을 때만 채워진다. 그냥 대화만 했으면 빈 배열이다 (null 이 아니다).
     */
    List<AiRecommendedTrack> tracks

) {

}
