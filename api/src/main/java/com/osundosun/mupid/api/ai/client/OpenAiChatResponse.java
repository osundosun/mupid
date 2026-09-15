package com.osundosun.mupid.api.ai.client;

import java.util.List;

/**
 * `POST /chat/completions` 응답 본문. 쓰는 값만 담는다 (`usage` 등은 무시).
 */
public record OpenAiChatResponse(

    List<Choice> choices

) {

    public record Choice(

        Integer index,

        OpenAiMessage message,

        /** `stop` · `tool_calls` · `length` 등. 판단은 메시지 내용으로 하고 참고만 한다. */
        String finishReason

    ) {
    }

    /**
     * `n` 을 보내지 않으므로 후보는 항상 하나다. 없으면 null.
     */
    public OpenAiMessage firstMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).message();
    }

}
