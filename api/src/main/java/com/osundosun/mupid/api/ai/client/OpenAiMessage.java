package com.osundosun.mupid.api.ai.client;

import java.util.List;

/**
 * `messages[]` 한 칸. 요청과 응답이 같은 모양을 쓴다.
 *
 * 툴 호출을 한 번 거치면 대화는 이렇게 쌓인다.
 *
 * <pre>
 * developer  시스템 프롬프트
 * user       "조금 더 잔잔한 곡"
 * assistant  content=null, tool_calls=[recommend_music]   ← 모델이 돌려준 것을 그대로 되돌려준다
 * tool       tool_call_id=..., content={"tracks":[...]}   ← 우리가 실행한 결과
 * assistant  "이런 곡은 어떠세요…"                          ← 최종 답변
 * </pre>
 *
 * 세 번째 칸을 빼먹으면 게이트웨이가 `tool` 메시지를 거부한다. 툴 결과는 반드시 자신을
 * 부른 assistant 메시지 **뒤에** 와야 한다.
 */
public record OpenAiMessage(

    String role,

    String content,

    List<OpenAiToolCall> toolCalls,

    String toolCallId

) {

    /**
     * 추론 모델의 시스템 프롬프트 역할. gpt-5 계열은 `system` 대신 이 이름을 쓴다.
     */
    public static OpenAiMessage developer(String content) {
        return new OpenAiMessage("developer", content, null, null);
    }

    public static OpenAiMessage user(String content) {
        return new OpenAiMessage("user", content, null, null);
    }

    public static OpenAiMessage assistant(String content) {
        return new OpenAiMessage("assistant", content, null, null);
    }

    /**
     * 툴 실행 결과. `toolCallId` 는 모델이 준 호출 id 를 그대로 돌려줘야 한다.
     */
    public static OpenAiMessage tool(String toolCallId, String content) {
        return new OpenAiMessage("tool", content, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

}
