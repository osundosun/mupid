package com.osundosun.mupid.api.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.client.AiJson;
import com.osundosun.mupid.api.ai.client.OpenAiChatRequest;
import com.osundosun.mupid.api.ai.client.OpenAiClient;
import com.osundosun.mupid.api.ai.client.OpenAiMessage;
import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.OpenAiToolCall;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.tool.AiRecommendTool;
import com.osundosun.mupid.api.ai.tool.TestMusicSummaries;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.user.entity.User;

/**
 * 437 — <b>추천을 한 번 청하면 추천은 한 번만 돈다.</b>
 *
 * <p>증상은 "추천해 줘" 한 마디에 추천 툴이 연달아 두 번 돌아 화면에 "1번째·2번째 음악 추천"
 * 이 붙고 두 묶음의 곡이 섞이는 것이었다. 길은 둘이다.
 * <ul>
 *   <li>한 응답에 {@code recommend_music} 호출이 둘 실려 온다 — {@code parallel_tool_calls}
 *       를 보내지 않았고, 서버는 실린 호출을 전부 실행했다.</li>
 *   <li>툴 결과를 받은 모델이 조건을 바꿔 다시 부른다 — 결과 뒤에도 툴이 열려 있었고,
 *       빈 결과·실패 메시지가 "조건을 줄이거나", "다시 시도" 로 다시 부르기를 권했다.</li>
 * </ul>
 * 게이트웨이와 추천 툴은 Mockito 목이다. 요청 본문 모양은 {@link OpenAiChatRequest#of} 로 본다.
 */
class AiChatDuplicateRecommendTest {

    private final User user = User.builder().userId(15).build();

    private AiProperties props() {
        return new AiProperties("http://gateway.invalid", "test-key", "gpt-5-mini",
            Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20);
    }

    private AiChatHistoryService history() {
        AiChatHistoryService stub = mock(AiChatHistoryService.class);
        when(stub.open(any(), any())).thenReturn(AiChatSession.builder().aiChatSessionId(1).build());
        return stub;
    }

    private OpenAiTool declaration() {
        return OpenAiTool.function(AiRecommendTool.NAME, "곡을 찾는다", Map.of("type", "object"));
    }

    private AiRecommendTool tool() {
        AiRecommendTool tool = mock(AiRecommendTool.class);
        when(tool.declaration()).thenReturn(declaration());
        return tool;
    }

    private AiChatServiceImpl service(OpenAiClient ai, AiRecommendTool tool) {
        return new AiChatServiceImpl(ai, tool, null, props(), history());
    }

    /** 호출마다 준비한 답을 차례로 돌려주고, 보낸 메시지를 그 시점 그대로 복사해 둔다. */
    private OpenAiClient gateway(List<List<OpenAiMessage>> sent, OpenAiMessage... answers) {
        OpenAiClient ai = mock(OpenAiClient.class);
        int[] call = {0};
        when(ai.complete(anyList(), anyList())).thenAnswer(invocation -> {
            sent.add(new ArrayList<>(invocation.getArgument(0)));
            return answers[Math.min(call[0]++, answers.length - 1)];
        });
        return ai;
    }

    private OpenAiToolCall call(String id, String query) {
        return new OpenAiToolCall(id, "function", new OpenAiToolCall.Function(
            AiRecommendTool.NAME, "{\"query\":\"%s\"}".formatted(query)));
    }

    private OpenAiMessage toolCalls(OpenAiToolCall... calls) {
        return new OpenAiMessage("assistant", null, List.of(calls), null);
    }

    private AiRecommendedTrack track(int musicId) {
        Music music = Music.builder()
            .musicId(musicId)
            .title("곡 " + musicId)
            .artist(Artist.builder().artistId(1).name("아티스트").build())
            .album(Album.builder().albumId(1).title("앨범").build())
            .build();
        return new AiRecommendedTrack(1, TestMusicSummaries.summaryOf(music), 10, null,
            30_000, 60_000, 0.9, "QUERY_FILTER_MATCH", "잔잔한 결");
    }

    private List<OpenAiMessage> toolMessages(List<OpenAiMessage> messages) {
        return messages.stream().filter(m -> "tool".equals(m.role())).toList();
    }

    // ------------------------------------------------------------ 서버 루프

    @Test
    @DisplayName("한 응답에 recommend_music 이 둘 실려도 추천은 한 번만 실행하고, 호출 id 마다 결과는 돌려준다")
    void twoCallsInOneMessageRunOnce() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        when(tool.recommend(eq(15), any(), any()))
            .thenReturn(new AiRecommendTool.Result(7L, List.of(track(101))))
            .thenReturn(new AiRecommendTool.Result(8L, List.of(track(202))));
        AiChatServiceImpl service = service(gateway(sent,
            toolCalls(call("call_1", "잔잔한 곡"), call("call_2", "잔잔하고 차분한 곡")),
            OpenAiMessage.assistant("잔잔한 결로 골라봤어요.")), tool);

        AiChatResponse response = service.chat(user, new AiChatRequest("잔잔한 곡 추천해줘", List.of(), null, null));

        verify(tool, times(1)).recommend(eq(15), any(), any());
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().arguments()).containsEntry("query", "잔잔한 곡");
        assertThat(response.tracks()).extracting(AiRecommendedTrack::musicId).containsExactly(101);
        assertThat(response.recommendationId()).isEqualTo(7L);
        // 게이트웨이는 호출 id 마다 tool 메시지를 요구한다. 실행하지 않은 쪽에도 답은 간다.
        assertThat(toolMessages(sent.get(1))).extracting(OpenAiMessage::toolCallId)
            .containsExactly("call_1", "call_2");
        assertThat(toolMessages(sent.get(1)).get(1).content()).contains("툴을 다시 부르지 말고");
    }

    @Test
    @DisplayName("툴 결과를 받은 모델이 말을 바꿔 다시 불러도 추천은 한 번만 실행한다")
    void secondRoundCallDoesNotRunAgain() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        when(tool.recommend(eq(15), any(), any()))
            .thenReturn(new AiRecommendTool.Result(7L, List.of(track(101))))
            .thenReturn(new AiRecommendTool.Result(8L, List.of(track(202))));
        AiChatServiceImpl service = service(gateway(sent,
            toolCalls(call("call_1", "비 오는 날 듣기 좋은 곡, 차분한 피아노 중심")),
            toolCalls(call("call_2", "비 오는 날 듣기 좋은, 차분한 피아노 중심")),
            OpenAiMessage.assistant("비 오는 날의 피아노 결로 골라봤어요.")), tool);

        AiChatResponse response = service.chat(user, new AiChatRequest("비 오는 날 노래 추천해줘", List.of(), null, null));

        verify(tool, times(1)).recommend(eq(15), any(), any());
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.tracks()).extracting(AiRecommendedTrack::musicId).containsExactly(101);
        assertThat(response.recommendationId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("추천 서버가 죽어 실패해도 다시 부른 호출은 실행하지 않는다 — 실패 기록 한 줄")
    void failedRecommendIsNotRetried() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        when(tool.recommend(eq(15), any(), any()))
            .thenThrow(new BusinessException(AiErrorCode.AI_REQUEST_FAILED));
        AiChatServiceImpl service = service(gateway(sent,
            toolCalls(call("call_1", "신나는 곡")),
            toolCalls(call("call_2", "신나는 느낌의 곡")),
            OpenAiMessage.assistant("지금은 곡을 찾아 드릴 수 없어요.")), tool);

        AiChatResponse response = service.chat(user, new AiChatRequest("신나는 곡 추천해줘", List.of(), null, null));

        verify(tool, times(1)).recommend(eq(15), any(), any());
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().succeeded()).isFalse();
        // 실패 메시지가 모델에게 다시 시도하라고 하지 않는다.
        String failure = toolMessages(sent.get(1)).getFirst().content();
        assertThat(failure).doesNotContain("다시 시도");
        assertThat(failure).contains("툴을 다시 부르지 말고");
    }

    @Test
    @DisplayName("빈 결과 메시지가 조건을 바꿔 다시 부르라고 하지 않는다 — 사용자에게 말로 묻게 한다")
    void emptyResultDoesNotInviteRetry() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        when(tool.recommend(eq(15), any(), any())).thenReturn(AiRecommendTool.Result.empty());
        service(gateway(sent,
            toolCalls(call("call_1", "재즈 힙합")),
            OpenAiMessage.assistant("조건에 맞는 곡이 없었어요.")), tool)
            .chat(user, new AiChatRequest("재즈 힙합 추천해줘", List.of(), null, null));

        String empty = toolMessages(sent.get(1)).getFirst().content();
        assertThat(empty).contains("툴을 다시 부르지 말고");
        assertThat(empty).contains("사용자에게 답하세요");
    }

    // ------------------------------------------------------------ 요청 본문

    private String body(List<OpenAiMessage> messages) {
        return AiJson.writeString(OpenAiChatRequest.of("gpt-5-mini", messages, List.of(declaration())));
    }

    @Test
    @DisplayName("툴을 선언하면 parallel_tool_calls=false 를 보낸다. 첫 호출에는 tool_choice 가 없다(436)")
    void firstRoundDisablesParallelCallsOnly() {
        String body = body(List.of(OpenAiMessage.developer("p"), OpenAiMessage.user("추천해줘")));

        assertThat(body).contains("\"parallel_tool_calls\":false");
        assertThat(body).doesNotContain("tool_choice");
    }

    @Test
    @DisplayName("이번 사용자 말 뒤에 추천을 이미 불렀으면 tool_choice=none 으로 툴을 닫는다")
    void closesToolsAfterToolResult() {
        String body = body(List.of(
            OpenAiMessage.developer("p"),
            OpenAiMessage.user("추천해줘"),
            toolCalls(call("call_1", "잔잔한 곡")),
            OpenAiMessage.tool("call_1", "{\"tracks\":[]}")));

        assertThat(body).contains("\"tool_choice\":\"none\"");
        assertThat(body).contains("\"parallel_tool_calls\":false");
    }

    @Test
    @DisplayName("선언하지 않은 이름을 부른 것은 셈하지 않는다 — 추천은 아직 열려 있다")
    void undeclaredCallDoesNotCloseTools() {
        String body = body(List.of(
            OpenAiMessage.user("추천해줘"),
            new OpenAiMessage("assistant", null, List.of(new OpenAiToolCall("call_1", "function",
                new OpenAiToolCall.Function("search_web", "{}"))), null),
            OpenAiMessage.tool("call_1", "{\"error\":\"없다\"}")));

        assertThat(body).doesNotContain("tool_choice");
    }

    @Test
    @DisplayName("툴이 없으면 parallel_tool_calls·tool_choice 를 보내지 않는다 — tools 없이 받으면 거부된다")
    void noToolsSendsNeitherOption() {
        String body = AiJson.writeString(OpenAiChatRequest.of("gpt-5-mini",
            List.of(OpenAiMessage.user("안녕")), List.of()));

        assertThat(body).doesNotContain("parallel_tool_calls");
        assertThat(body).doesNotContain("tool_choice");
    }
}
