package com.osundosun.mupid.api.ai.client;

/**
 * 모델이 요청한 툴 호출 하나.
 */
public record OpenAiToolCall(

    /** 결과 메시지의 `tool_call_id` 로 그대로 돌려줘야 하는 값. */
    String id,

    /** 지금은 항상 `function`. */
    String type,

    Function function

) {

    public record Function(

        String name,

        /**
         * JSON **문자열**이다. 객체가 아니다. 게다가 모델이 만든 값이라 스키마를
         * 어길 수 있어, 읽는 쪽이 실패를 감당해야 한다 — `AiJson.readArguments`.
         */
        String arguments

    ) {
    }

}
