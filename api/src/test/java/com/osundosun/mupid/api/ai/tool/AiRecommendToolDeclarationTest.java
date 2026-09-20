package com.osundosun.mupid.api.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * 추천 서버가 실패해도 **툴 선언은 반드시 만들어진다.**
 *
 * <p>대화의 첫 문장이 툴 선언이고 그 선언이 추천 서버(`GET /internal/facets`)에 매달려
 * 있다. 여기서 예외가 새면 LLM 을 부르지도 못한 채 대화가 통째로 실패하는데, 사용자
 * 눈에는 정확히 "도구 호출이 안 되는" 상태로 보인다. 어휘를 못 받으면 enum 없이 진행하면
 * 된다 — {@code allowedValues} 가 빈 목록을 이미 감당한다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않는다. JDK 내장 HTTP 서버로 실패 모드를 만들고
 * {@link RecommenderClient} 를 직접 조립한다 — {@code ai/client/RecommenderClientTest} 와
 * 같은 스타일이다. MockWebServer 같은 새 의존성은 들이지 않는다.
 */
class AiRecommendToolDeclarationTest {

    /** 아무도 듣지 않는 포트. 연결이 즉시 거부된다 (DNS 를 기다리지 않는다). */
    private static final String REFUSED = "http://127.0.0.1:1";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private RecommenderClient client(String baseUrl) {
        RecommenderProperties props = new RecommenderProperties(
            baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2), true, null);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new RecommenderClient(RestClient.builder()
            .baseUrl(props.isConfigured() ? props.baseUrl() : "http://recommender.invalid")
            .requestFactory(factory)
            .build(), props);
    }

    /** 추천 서버만 죽은 툴. 나머지 협력자는 선언 경로에서 쓰이지 않으므로 null 이다. */
    private AiRecommendToolImpl tool(String baseUrl) {
        return new AiRecommendToolImpl(client(baseUrl), null, null, null, null);
    }

    /** 정해진 (상태, 본문) 을 돌려주는 대역 서버를 띄우고 그 주소의 툴을 준다. */
    private AiRecommendToolImpl toolBehind(int status, String body, AtomicInteger calls)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, b.length);
            exchange.getResponseBody().write(b);
            exchange.close();
        });
        server.start();
        return tool("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Map<String, Object> parameters(AiRecommendToolImpl tool) {
        return tool.declaration().function().parameters();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(AiRecommendToolImpl tool) {
        return (Map<String, Object>)parameters(tool).get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> condition(AiRecommendToolImpl tool, String slot) {
        Map<String, Object> spec = (Map<String, Object>)properties(tool).get(slot);
        return (Map<String, Object>)spec.get("properties");
    }

    // ------------------------------------------------------------ 실패해도 선언은 나온다

    @Test
    @DisplayName("추천 서버가 죽어 있어도(연결 거부) 툴 선언이 만들어진다 — 이름과 스키마가 온전하다")
    void declaresWhenRecommenderRefusesConnection() {
        AiRecommendToolImpl tool = tool(REFUSED);

        assertThatCode(tool::declaration).doesNotThrowAnyException();

        OpenAiTool declaration = tool.declaration();
        assertThat(declaration.function().name()).isEqualTo("recommend_music");
        // 계약이다 — 이름은 바뀌지 않는다.
        assertThat(declaration.function().name()).isEqualTo(AiRecommendTool.NAME);
        assertThat(declaration.type()).isEqualTo("function");
        // 어휘가 없어도 스키마의 뼈대는 그대로다.
        assertThat(parameters(tool)).containsEntry("type", "object");
        assertThat(parameters(tool)).containsEntry("required", List.of("query"));
        assertThat(properties(tool)).containsKeys("query", "limit", "filters", "exclude");
        assertThat(condition(tool, "filters")).containsKeys(
            "genre", "language", "vocal", "instrument", "mood", "flow");
    }

    @Test
    @DisplayName("추천 서버 주소가 아예 없어도 툴 선언이 만들어진다")
    void declaresWhenRecommenderNotConfigured() {
        AiRecommendToolImpl tool = tool("  ");

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(tool.declaration().function().name()).isEqualTo("recommend_music");
    }

    @Test
    @DisplayName("추천 서버가 503 이어도 툴 선언이 만들어진다")
    void declaresWhenRecommenderUnavailable() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AiRecommendToolImpl tool = toolBehind(503,
            "{ \"error_code\": \"INDEX_NOT_READY\", \"message\": \"색인 미완\" }", calls);

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(tool.declaration().function().name()).isEqualTo("recommend_music");
    }

    @Test
    @DisplayName("추천 서버가 500 이어도 툴 선언이 만들어진다")
    void declaresWhenRecommenderErrors() throws IOException {
        AiRecommendToolImpl tool = toolBehind(500,
            "{ \"error_code\": \"BOOM\", \"message\": \"서버 오류\" }", new AtomicInteger());

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(tool.declaration().function().name()).isEqualTo("recommend_music");
    }

    @Test
    @DisplayName("추천 서버가 인증을 거부해도(401) 툴 선언이 만들어진다")
    void declaresWhenRecommenderRejectsToken() throws IOException {
        AiRecommendToolImpl tool = toolBehind(401,
            "{ \"error_code\": \"UNAUTHORIZED\", \"message\": \"토큰 불일치\" }", new AtomicInteger());

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(tool.declaration().function().name()).isEqualTo("recommend_music");
    }

    @Test
    @DisplayName("추천 서버가 JSON 이 아닌 것을 돌려줘도 툴 선언이 만들어진다")
    void declaresWhenRecommenderReturnsGarbage() throws IOException {
        AiRecommendToolImpl tool = toolBehind(200, "<html>프록시 오류</html>", new AtomicInteger());

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(tool.declaration().function().name()).isEqualTo("recommend_music");
    }

    @Test
    @DisplayName("어휘 응답이 계약과 달라도(필드 타입 불일치) 툴 선언이 만들어진다")
    void declaresWhenFacetsShapeIsUnexpected() throws IOException {
        AiRecommendToolImpl tool = toolBehind(200,
            "{ \"catalog\": {}, \"fields\": [1, 2, 3] }", new AtomicInteger());

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        assertThat(condition(tool, "filters")).containsKeys("genre", "mood");
    }

    @Test
    @DisplayName("어휘를 못 받아도 조건 칸은 사라지지 않는다 — enum 만 빠진다")
    void fallsBackToFreeTextWithoutVocabulary() {
        AiRecommendToolImpl tool = tool(REFUSED);

        Map<String, Object> genre = (Map<String, Object>)condition(tool, "filters").get("genre");

        // 조건이 조용히 사라지면 모델이 조건을 걸 방법 자체가 없어진다.
        assertThat(genre).isNotNull();
        assertThat(genre).containsEntry("type", "array");
        assertThat(genre).doesNotContainKey("enum");
        assertThat((Map<String, Object>)genre.get("items")).containsEntry("type", "string");
    }

    // ------------------------------------------------------------ 어휘를 받으면 enum 이 붙는다

    @Test
    @DisplayName("어휘를 받으면 enum 이 채워진다 — 실패 경로와 정상 경로가 같은 뼈대다")
    void fillsEnumWhenVocabularyAvailable() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AiRecommendToolImpl tool = toolBehind(200, """
            { "catalog": { "tracks": 2 },
              "fields": { "genre": { "values": [ {"value": "pop", "tracks": 3},
                                                 {"value": "rock", "tracks": 1} ],
                                     "style_values": [ {"value": "ballad"} ] },
                          "flow": { "values": [ {"value": "chorus"} ] } } }
            """, calls);

        Map<String, Object> genre = (Map<String, Object>)condition(tool, "filters").get("genre");

        assertThat(genre).containsEntry("type", "array");
        // enum 은 배열이 아니라 그 원소(items)에 붙는다 — JSON Schema 규칙이다.
        assertThat((Map<String, Object>)genre.get("items"))
            .containsEntry("enum", List.of("pop", "rock", "ballad"));
        // 필드마다 값 목록의 출처가 다르다 — 장르 게이트는 genre·style 양쪽을 본다.
        assertThat(calls).hasValue(1);
    }

    // ------------------------------------------------------------ 턴당 비용

    @Test
    @DisplayName("어휘 조회는 선언 하나당 한 번만 한다 — 대화 한 턴이 추천 서버를 두 번 때리지 않는다")
    void fetchesVocabularyOncePerDeclaration() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AiRecommendToolImpl tool = toolBehind(503,
            "{ \"error_code\": \"INDEX_NOT_READY\", \"message\": \"\" }", calls);

        tool.declaration();

        // 예전에는 conditionSchema 가 filters·exclude 마다 facets() 를 불러 2회였다.
        // 실패는 캐시되지 않으므로 추천 서버가 죽으면 대화마다 연결 타임아웃을 두 번
        // 기다렸다 — 실측 4초(연결 타임아웃 2초 x 2).
        assertThat(calls).hasValue(1);
    }
}
