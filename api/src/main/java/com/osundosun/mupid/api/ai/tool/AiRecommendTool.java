package com.osundosun.mupid.api.ai.tool;

import java.util.List;
import java.util.Map;

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
         * 사용자가 원한 느낌을 한 문장으로. **조건을 거는 데 쓰이지 않는다.**
         *
         * 추천 서버에 원문을 보내려면 자연어 해석기가 따로 떠 있어야 한다(없으면 501).
         * 우리는 해석기 대신 대화 모델이 뽑은 `filters`·`exclude` 를 보내므로, 이 문장은
         * 로그와 "무엇을 찾았는지" 를 사람이 읽기 위한 자리다.
         */
        String query,

        /** 몇 곡을 받을지. 구현이 1~5 로 자른다. */
        Integer limit,

        /**
         * 반드시 만족해야 하는 조건. 값은 `/internal/facets` 의 어휘여야 한다.
         *
         * 예: `{"genre": ["pop"], "instrument": ["piano"], "vocal": ["female"]}`
         */
        Map<String, Object> filters,

        /**
         * 빼야 하는 조건. 같은 어휘를 쓴다. 예: `{"mood": ["aggressive"]}`
         */
        Map<String, Object> exclude

    ) {
    }

}
