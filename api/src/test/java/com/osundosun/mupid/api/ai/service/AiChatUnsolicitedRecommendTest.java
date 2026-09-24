package com.osundosun.mupid.api.ai.service;

import com.osundosun.mupid.api.ai.catalog.TestCatalogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.dto.AiChatTurn;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.tool.AiRecommendTool;
import com.osundosun.mupid.api.ai.tool.AiRecommendToolImpl;
import com.osundosun.mupid.api.ai.tool.TestMusicSummaries;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.user.entity.User;

/**
 * 436 — <b>묻지 않았는데 곡을 추천하지 않는다.</b>
 *
 * <p>증상은 "인사만 해도 추천곡 카드가 붙는다" 였다. 코드가 툴을 강제하는 곳은 없었다
 * (요청에 {@code tool_choice} 가 없고, 툴은 모델이 부를 때만 돈다). 모델을 그쪽으로 민 것은
 * 모델에게 가는 문장이었다.
 * <ul>
 *   <li>프롬프트·툴 설명에 "추천을 원하면 부른다" 만 있고 <b>부르지 않을 때</b>가 없었다.</li>
 *   <li>화면의 인사말("새로운 음악을 찾아볼까요? 원하는 느낌을 들려주세요.")이 AI 말로
 *       기록에 실려, 모델은 자기가 먼저 곡을 권했다고 읽었다.</li>
 * </ul>
 *
 * <p>그래서 여기서 못박는 것은 셋이다. 추천 여부는 <b>모델의 툴 호출 하나로만</b> 갈린다 ·
 * 모델에게 가는 문장이 부르지 않을 때를 말한다 · 인사말이 기록에 실리지 않는다.
 * 게이트웨이와 추천 툴은 Mockito 목이다 — 이 테스트의 관심사는 HTTP 가 아니라 분기다.
 */
class AiChatUnsolicitedRecommendTest {

    private static final String GREETING =
        "지금 듣는 곡의 저장한 순간에서 새로운 음악을 찾아볼까요? 원하는 느낌을 들려주세요.";

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

    private AiRecommendTool tool() {
        AiRecommendTool tool = mock(AiRecommendTool.class);
        when(tool.declaration()).thenReturn(
            OpenAiTool.function(AiRecommendTool.NAME, "곡을 찾는다", Map.of("type", "object")));
        return tool;
    }

    private AiChatServiceImpl service(OpenAiClient ai, AiRecommendTool tool) {
        return new AiChatServiceImpl(ai, tool, null, props(), history());
    }

    /**
     * 게이트웨이 목. 호출마다 준비한 답을 차례로 돌려주고, 보낸 메시지를 그 시점 그대로 복사해 둔다
     * (서비스가 같은 리스트에 이어 붙이므로 복사하지 않으면 마지막 모양만 남는다).
     */
    private OpenAiClient gateway(List<List<OpenAiMessage>> sent, OpenAiMessage... answers) {
        OpenAiClient ai = mock(OpenAiClient.class);
        int[] call = {0};
        when(ai.complete(anyList(), anyList())).thenAnswer(invocation -> {
            sent.add(new ArrayList<>(invocation.getArgument(0)));
            return answers[Math.min(call[0]++, answers.length - 1)];
        });
        return ai;
    }

    private OpenAiMessage toolCall(String arguments) {
        return new OpenAiMessage("assistant", null, List.of(new OpenAiToolCall(
            "call_1", "function", new OpenAiToolCall.Function(AiRecommendTool.NAME, arguments))), null);
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

    // ------------------------------------------------------------ 분기

    @Test
    @DisplayName("추천을 요청하지 않은 턴 — 모델이 툴을 부르지 않으면 추천 툴도 곡 카드도 없다")
    void plainTalkCarriesNoTracks() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        AiChatServiceImpl service = service(
            gateway(sent, OpenAiMessage.assistant("안녕하세요. 오늘은 어떤 곡을 듣고 계세요?")), tool);

        AiChatResponse response = service.chat(user,
            new AiChatRequest("안녕", List.of(new AiChatTurn(AiMessageType.AI, GREETING)), null, null));

        assertThat(response.reply()).isNotBlank();
        assertThat(response.tracks()).isEmpty();
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.recommendationId()).isNull();
        // 서버가 스스로 추천을 부르는 경로가 없다 — 게이트웨이 한 번으로 끝난다.
        assertThat(sent).hasSize(1);
        verify(tool, never()).recommend(any(), any(), any());
    }

    @Test
    @DisplayName("추천을 요청한 턴 — 모델이 툴을 부르면 기존처럼 곡과 기록이 실린다")
    void requestedTurnCarriesTracks() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        AiRecommendTool tool = tool();
        when(tool.recommend(eq(15), any(), any()))
            .thenReturn(new AiRecommendTool.Result(7L, List.of(track(101))));
        AiChatServiceImpl service = service(gateway(sent,
            toolCall("{\"query\":\"지금 곡과 비슷한 잔잔한 곡\"}"),
            OpenAiMessage.assistant("지금 곡처럼 잔잔한 결로 골라봤어요.")), tool);

        AiChatResponse response = service.chat(user,
            new AiChatRequest("이 곡이랑 비슷한 노래 틀어줘", List.of(), null, null));

        verify(tool, times(1)).recommend(eq(15), any(), any());
        assertThat(response.tracks()).extracting(AiRecommendedTrack::musicId).containsExactly(101);
        assertThat(response.recommendationId()).isEqualTo(7L);
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().succeeded()).isTrue();
    }

    @Test
    @DisplayName("요청 본문에 tool_choice 가 없다 — 툴을 부를지는 모델이 매 턴 고른다")
    void requestNeverForcesTool() {
        String body = AiJson.writeString(new OpenAiChatRequest("gpt-5-mini",
            List.of(OpenAiMessage.user("안녕")),
            List.of(OpenAiTool.function(AiRecommendTool.NAME, "곡을 찾는다", Map.of()))));

        assertThat(body).contains("\"tools\"");
        assertThat(body).doesNotContain("tool_choice");
    }

    // ------------------------------------------------------------ 모델에게 가는 문장

    @Test
    @DisplayName("프롬프트가 툴을 부르지 않을 때를 말하고, 무관한 질문을 추천으로 돌리라고 하지 않는다")
    void developerPromptSaysWhenNotToRecommend() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        service(gateway(sent, OpenAiMessage.assistant("네.")), tool())
            .chat(user, new AiChatRequest("고마워", List.of(), null, null));

        String prompt = sent.getFirst().getFirst().content();
        assertThat(sent.getFirst().getFirst().role()).isEqualTo("developer");
        assertThat(prompt).contains("곡을 찾아 달라고 할 때만 부릅니다");
        assertThat(prompt).contains("툴을 부르지 않고 말로만 답합니다");
        assertThat(prompt).contains("묻지 않은 곡을 먼저 권하지 않습니다");
        // 요청한 경우의 규칙은 그대로 남아 있어야 한다.
        assertThat(prompt).contains("반드시 recommend_music 툴을 먼저 부릅니다");
        assertThat(prompt).doesNotContain("음악 이야기로 돌아옵니다");
    }

    @Test
    @DisplayName("툴 설명도 요청이 없는 턴에는 부르지 않는다고 말한다")
    void toolDescriptionSaysWhenNotToCall() {
        // 어휘를 못 받는 추천 서버(facets 가 null). 설명 문장은 어휘와 무관하다.
        AiRecommendToolImpl impl = new AiRecommendToolImpl(
            mock(RecommenderClient.class), TestCatalogs.fixed(), null, null, null, null, null);

        String description = impl.declaration().function().description();

        assertThat(description).contains("곡을 찾아 달라는 말이 없는 턴에는 부르지 않는다");
        assertThat(description).contains("반드시 이 툴을 먼저 부른다");
    }

    // ------------------------------------------------------------ 이전 대화

    @Test
    @DisplayName("화면 인사말(사용자가 말하기 전의 AI 말)은 모델에게 보내지 않는다")
    void dropsScreenGreetingBeforeFirstUserTurn() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        service(gateway(sent, OpenAiMessage.assistant("안녕하세요.")), tool())
            .chat(user, new AiChatRequest("안녕",
                List.of(new AiChatTurn(AiMessageType.AI, GREETING)), null, null));

        List<OpenAiMessage> messages = sent.getFirst();
        assertThat(messages).extracting(OpenAiMessage::role).containsExactly("developer", "user");
        assertThat(messages).extracting(OpenAiMessage::content).doesNotContain(GREETING);
    }

    @Test
    @DisplayName("사용자가 말한 뒤의 AI 답은 그대로 싣는다 — 잘라 내는 것은 앞머리 인사말뿐이다")
    void keepsRealRepliesAfterFirstUserTurn() {
        List<List<OpenAiMessage>> sent = new ArrayList<>();
        service(gateway(sent, OpenAiMessage.assistant("천만에요.")), tool())
            .chat(user, new AiChatRequest("고마워", List.of(
                new AiChatTurn(AiMessageType.AI, GREETING),
                new AiChatTurn(AiMessageType.USER, "잔잔한 곡 추천해줘"),
                new AiChatTurn(AiMessageType.AI, "잔잔한 결로 골라봤어요.")), null, null));

        assertThat(sent.getFirst()).extracting(OpenAiMessage::content).containsSubsequence(
            "잔잔한 곡 추천해줘", "잔잔한 결로 골라봤어요.", "고마워");
        assertThat(sent.getFirst()).extracting(OpenAiMessage::content).doesNotContain(GREETING);
    }
}
