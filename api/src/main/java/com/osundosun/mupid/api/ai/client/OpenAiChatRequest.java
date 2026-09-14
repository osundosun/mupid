package com.osundosun.mupid.api.ai.client;

import java.util.List;

/**
 * `POST /chat/completions` 요청 본문.
 *
 * `temperature`·`max_tokens` 를 보내지 않는다. gpt-5 계열은 두 값을 거부하거나
 * (`max_tokens` → `max_completion_tokens`) 기본값만 받는다. 필요해지면 모델별로
 * 갈라야 하므로 지금은 최소한만 보낸다.
 */
public record OpenAiChatRequest(

    String model,

    List<OpenAiMessage> messages,

    /** 비우면 툴 없이 대화만 한다. */
    List<OpenAiTool> tools

) {

}
