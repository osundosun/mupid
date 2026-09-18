package com.osundosun.mupid.api.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.client.OpenAiClient;
import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.dto.AiChatTurn;
import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.tool.AiRecommendTool;
import com.osundosun.mupid.api.ai.tool.AiRecommendToolImpl;
import com.osundosun.mupid.api.user.entity.User;
import com.sun.net.httpserver.HttpServer;

/**
 * 대화 한 번의 **첫 문장**(툴 선언)이 추천 서버 장애에 매달리지 않는지 본다.
 *
 * <p>게이트웨이 대역과 추천 서버 대역을 JDK 내장 HTTP 서버로 세우고, Spring 컨텍스트 없이
 * 조립한다. 추천 서버가 죽어 있어도 첫 요청 본문에 {@code tools} 가 실리고 그 이름이
 * {@code recommend_music} 인 것 — 즉 사용자 눈에 "도구 호출이 되는" 상태인 것을 못박는다.
 */
class AiChatServiceToolDeclarationTest {

    /** 아무도 듣지 않는 포트. 연결이 즉시 거부된다. */
    private static final String DEAD_RECOMMENDER = "http://127.0.0.1:1";

    private HttpServer gateway;
    private final List<String> gatewayRequests = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop(0);
        }
    }

    private AiProperties aiProps(String baseUrl) {
        return new AiProperties(baseUrl, "test-key", "gpt-5-mini",
            Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20);
    }

    /** 모델이 바로 본문으로 답하는 게이트웨이 대역. */
    private OpenAiClient gateway(String answer) throws IOException {
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            gatewayRequests.add(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] body = ("""
                {"choices":[{"index":0,"finish_reason":"stop",
                 "message":{"role":"assistant","content":"%s"}}]}
                """).formatted(answer).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gateway.start();
        AiProperties props = aiProps("http://127.0.0.1:" + gateway.getAddress().getPort());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new OpenAiClient(RestClient.builder()
            .baseUrl(props.baseUrl()).requestFactory(factory).build(), props);
    }

    private AiRecommendToolImpl tool(String recommenderBaseUrl) {
        RecommenderProperties props = new RecommenderProperties(
            recommenderBaseUrl, Duration.ofMillis(200), Duration.ofMillis(200), true, null);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new AiRecommendToolImpl(new RecommenderClient(RestClient.builder()
            .baseUrl(props.isConfigured() ? props.baseUrl() : "http://recommender.invalid")
            .requestFactory(factory).build(), props), null, null, null);
    }

    private AiChatServiceImpl service(OpenAiClient ai, AiRecommendTool tool) {
        return new AiChatServiceImpl(ai, tool, null, aiProps("x"));
    }

    @Test
    @DisplayName("추천 서버가 죽어도 대화는 툴을 선언한 채로 나간다 — 사용자에게는 도구 호출이 되는 상태다")
    void declaresToolEvenWhenRecommenderIsDown() throws IOException {
        AiChatServiceImpl service = service(
            gateway("잔잔한 곡을 찾아볼게요."), tool(DEAD_RECOMMENDER));

        AiChatResponse response = service.chat(User.builder().userId(15).build(),
            new AiChatRequest("조금 더 잔잔한 곡", List.of(), null));

        assertThat(response.reply()).isNotBlank();
        assertThat(gatewayRequests).hasSize(1);
        String first = gatewayRequests.getFirst();
        // 첫 요청에 툴이 실렸다 = LLM 이 recommend_music 을 부를 수 있다.
        assertThat(first).contains("\"tools\"");
        assertThat(first).contains("\"name\":\"recommend_music\"");
        assertThat(first).contains("\"required\":[\"query\"]");
    }

    @Test
    @DisplayName("선언 자체가 실패해도 대화를 끊지 않는다 — 툴 없이 이어 간다")
    void keepsConversationWhenDeclarationFails() throws IOException {
        // 계약이 깨졌을 때의 마지막 그물을 본다. 정상 경로에서는 일어나지 않는다.
        AiRecommendTool exploding = new AiRecommendTool() {
            @Override
            public OpenAiTool declaration() {
                throw new IllegalStateException("선언 불가");
            }

            @Override
            public Result recommend(
                Integer userId, Integer currentMusicId, Arguments arguments) {
                throw new IllegalStateException("선언이 없으면 불릴 일이 없다");
            }
        };
        AiChatServiceImpl service = service(gateway("그냥 대화만 할게요."), exploding);

        AiChatResponse response = service.chat(User.builder().userId(15).build(),
            new AiChatRequest("안녕", List.of(), null));

        assertThat(response.reply()).isNotBlank();
        // tools 가 빈 배열이어도 요청 자체는 나가야 한다.
        assertThat(gatewayRequests).hasSize(1);
        assertThat(gatewayRequests.getFirst()).contains("\"tools\":[]");
    }

    @Test
    @DisplayName("시스템 프롬프트가 모델에게 '순간' 으로 말한다 — '구간' 이 남아 있지 않다")
    void developerPromptUsesMomentWording() throws IOException {
        AiChatServiceImpl service = service(gateway("네."), tool(DEAD_RECOMMENDER));

        service.chat(User.builder().userId(15).build(),
            new AiChatRequest("추천", List.of(new AiChatTurn(AiMessageType.AI, "이전")), null));

        String sent = gatewayRequests.getFirst();
        assertThat(sent).contains("저장한 순간");
        assertThat(sent).contains("곡에서 마음에 남은 순간");
        // 화면 문구가 아니라 프롬프트만 바꾸는 것이 이 티켓이다. 모델에게 가는 문장에는
        // '구간' 이 남아 있으면 안 된다.
        assertThat(sent).doesNotContain("구간");
    }
}
