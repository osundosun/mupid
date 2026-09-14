package com.osundosun.mupid.api.ai.tool;

import java.util.List;

import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;

/**
 * 모델이 곡을 고를 때 부르는 툴.
 *
 * 대화는 LLM 이 하고 **곡 선정은 LLM 이 하지 않는다.** 모델에게 카탈로그를 통째로 물릴 수도
 * 없고, 물려도 없는 곡을 지어내기 때문이다. 모델은 사용자가 원한 느낌을 한 문장으로 정리해
 * 이 툴에 넘기고, 어떤 곡이 나올지는 추천 파이프라인이 정한다.
 */
public interface AiRecommendTool {

    /** 모델에게 노출되는 이름. 응답의 `tool_calls[].function.name` 과 같아야 한다. */
    String NAME = "recommend_music";

    /**
     * 모델에게 알려 줄 툴 선언(JSON Schema).
     */
    OpenAiTool declaration();

    /**
     * @param userId          추천 대상. 취향·청취 이력이 여기에 묶인다
     * @param currentMusicId  지금 듣고 있는 곡. 없으면 null. 결과에서 제외한다
     * @param arguments       모델이 만든 인자. 깨져 있을 수 있어 구현이 기본값을 채운다
     */
    List<AiRecommendedTrack> recommend(Integer userId, Integer currentMusicId, Arguments arguments);

    /**
     * 툴 인자. 모델이 JSON 문자열로 만들어 보내므로 **모든 칸이 비어 있을 수 있다.**
     */
    record Arguments(

        /**
         * 사용자가 원한 느낌을 한 문장으로.
         * 추천 서버의 `/internal/recommendations/search` 의 `query` 가 된다.
         */
        String query,

        /** 몇 곡을 받을지. 구현이 1~5 로 자른다. */
        Integer limit

    ) {
    }

}
