package com.osundosun.mupid.api.ai.client;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * `POST /chat/completions` 요청 본문.
 *
 * `temperature`·`max_tokens` 를 보내지 않는다. gpt-5 계열은 두 값을 거부하거나
 * (`max_tokens` → `max_completion_tokens`) 기본값만 받는다. 필요해지면 모델별로
 * 갈라야 하므로 지금은 최소한만 보낸다.
 *
 * <p>{@code toolChoice}·{@code parallelToolCalls} 는 null 이면 본문에서 빠진다
 * ({@link AiJson} 이 NON_NULL). 값은 {@link #of} 가 대화 모양을 보고 정한다.
 */
public record OpenAiChatRequest(

    String model,

    List<OpenAiMessage> messages,

    /** 비우면 툴 없이 대화만 한다. */
    List<OpenAiTool> tools,

    /** {@code "none"} 이면 이번 호출에서 툴을 부르지 못한다. null 이면 모델이 고른다(auto). */
    String toolChoice,

    /** false 면 한 응답에 툴 호출이 하나뿐이다. */
    Boolean parallelToolCalls

) {

    public OpenAiChatRequest(String model, List<OpenAiMessage> messages, List<OpenAiTool> tools) {
        this(model, messages, tools, null, null);
    }

    /**
     * 요청을 만든다. <b>사용자 말 한 번에 툴 왕복은 한 번이다</b> (S15P21A204-437).
     *
     * <p>사용자가 추천을 한 번 청했는데 추천이 연달아 두 번 돌았다. 길은 둘이었다.
     * <ul>
     *   <li>한 응답에 {@code recommend_music} 호출이 둘 실려 왔다. {@code parallel_tool_calls}
     *       를 보내지 않으면 OpenAI 의 기본값은 true 다.</li>
     *   <li>툴 결과를 받은 모델이 말을 살짝 바꿔 한 번 더 불렀다. 툴 결과 뒤에도 툴을 계속
     *       열어 두었고, 빈 결과·실패 메시지가 "조건을 줄이거나", "다시 시도" 처럼 다시
     *       부르라는 말로 읽혔다.</li>
     * </ul>
     *
     * <p>그래서 툴이 있으면 {@code parallel_tool_calls=false} 를 보내고, <b>마지막 사용자 말
     * 뒤에 선언한 툴을 이미 부른 적이 있으면</b> {@code tool_choice="none"} 으로 닫는다 — 결과를
     * 받았으니 이제 말로 답할 차례다. 판단 재료는 넘겨받은 메시지뿐이라 이 클래스는 여전히
     * 상태를 갖지 않는다. 선언하지 않은 이름을 부른 것은 셈에 넣지 않는다(실행된 적이 없다).
     *
     * <p>툴이 비었으면 둘 다 보내지 않는다. OpenAI 는 {@code tools} 없이 두 값을 받으면 거부한다.
     */
    public static OpenAiChatRequest of(String model, List<OpenAiMessage> messages, List<OpenAiTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return new OpenAiChatRequest(model, messages, tools);
        }
        return new OpenAiChatRequest(model, messages, tools,
            calledSinceLastUser(messages, declaredNames(tools)) ? "none" : null,
            Boolean.FALSE);
    }

    private static Set<String> declaredNames(List<OpenAiTool> tools) {
        return tools.stream()
            .map(OpenAiTool::function)
            .filter(Objects::nonNull)
            .map(OpenAiTool.Function::name)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private static boolean calledSinceLastUser(List<OpenAiMessage> messages, Set<String> declared) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            OpenAiMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                return false;
            }
            if (message.hasToolCalls() && message.toolCalls().stream()
                .anyMatch(call -> call.function() != null && declared.contains(call.function().name()))) {
                return true;
            }
        }
        return false;
    }

}
