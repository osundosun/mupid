package com.osundosun.mupid.api.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM 게이트웨이와의 **와이어 포맷**만 본다. 스프링 컨텍스트도 DB 도 필요 없다.
 *
 * 이 형식이 어긋나면 대화가 실패하는 게 아니라 **툴 호출만 조용히 사라진다.**
 * (게이트웨이는 모르는 필드를 무시한다.) 그래서 눈으로 확인하기 어렵고 테스트로 못박는다.
 */
class AiJsonTest {

    @Test
    @DisplayName("요청은 snake_case 로 나가고 비어 있는 칸은 아예 빠진다")
    void writeRequest() {
        OpenAiChatRequest request = new OpenAiChatRequest(
            "gpt-5-mini",
            List.of(
                OpenAiMessage.developer("Answer in Korean"),
                OpenAiMessage.user("조금 더 잔잔한 곡")
            ),
            List.of(OpenAiTool.function(
                "recommend_music",
                "다음에 들을 곡을 찾는다",
                Map.of("type", "object", "required", List.of("query"))
            ))
        );

        String json = new String(AiJson.writeBytes(request), StandardCharsets.UTF_8);

        assertThat(json).contains("\"model\":\"gpt-5-mini\"");
        assertThat(json).contains("\"role\":\"developer\"");
        // 툴을 부르지 않은 메시지에 tool_calls / tool_call_id 가 실리면 게이트웨이가 거부한다.
        assertThat(json).doesNotContain("tool_call");
        // JSON Schema 의 예약어는 Map 키라 snake_case 변환 대상이 아니다.
        assertThat(json).contains("\"type\":\"function\"");
        assertThat(json).contains("\"name\":\"recommend_music\"");
        // 한글은 바이트 그대로 나간다. 유니코드 이스케이프가 아니다.
        assertThat(json).contains("조금 더 잔잔한 곡");
    }

    @Test
    @DisplayName("툴 호출 응답을 읽는다 — 모르는 필드는 무시한다")
    void readToolCallResponse() {
        String body = """
            {
              "id": "chatcmpl-1",
              "service_tier": "default",
              "usage": {"total_tokens": 42},
              "choices": [
                {
                  "index": 0,
                  "finish_reason": "tool_calls",
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_1",
                        "type": "function",
                        "function": {
                          "name": "recommend_music",
                          "arguments": "{\\"query\\":\\"잔잔한 곡\\",\\"limit\\":2}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """;

        OpenAiMessage message = AiJson
            .read(body.getBytes(StandardCharsets.UTF_8), OpenAiChatResponse.class)
            .firstMessage();

        assertThat(message).isNotNull();
        assertThat(message.content()).isNull();
        assertThat(message.hasToolCalls()).isTrue();

        OpenAiToolCall call = message.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_1");
        assertThat(call.function().name()).isEqualTo("recommend_music");

        var arguments = AiJson.readArguments(call.function().arguments(), ArgumentsFixture.class);
        assertThat(arguments).isNotNull();
        assertThat(arguments.query()).isEqualTo("잔잔한 곡");
        assertThat(arguments.limit()).isEqualTo(2);
    }

    @Test
    @DisplayName("본문만 있는 응답을 읽는다")
    void readTextResponse() {
        String body = """
            {"choices":[{"index":0,"finish_reason":"stop",
             "message":{"role":"assistant","content":"이런 결은 어떠세요?"}}]}
            """;

        OpenAiMessage message = AiJson
            .read(body.getBytes(StandardCharsets.UTF_8), OpenAiChatResponse.class)
            .firstMessage();

        assertThat(message.hasToolCalls()).isFalse();
        assertThat(message.content()).isEqualTo("이런 결은 어떠세요?");
    }

    @Test
    @DisplayName("모델이 망가진 인자를 보내면 예외 대신 null 이다")
    void readBrokenArguments() {
        assertThat(AiJson.readArguments("{ not json", ArgumentsFixture.class)).isNull();
        assertThat(AiJson.readArguments("", ArgumentsFixture.class)).isNull();
        assertThat(AiJson.readArguments(null, ArgumentsFixture.class)).isNull();
    }

    private record ArgumentsFixture(String query, Integer limit) {
    }

}
