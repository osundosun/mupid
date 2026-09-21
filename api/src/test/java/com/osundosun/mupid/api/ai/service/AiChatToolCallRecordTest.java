package com.osundosun.mupid.api.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.client.OpenAiClient;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.tool.AiRecommendToolImpl;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;
import com.osundosun.mupid.api.user.entity.User;
import com.sun.net.httpserver.HttpServer;

/**
 * 툴 호출 **기록**이 응답까지 오는가 (S15P21A204-409).
 *
 * <p>티켓의 두 번째 결함은 "화면에 어떤 도구를 호출했는지 기록이 없다" 였다. 그것을
 * 고치면서 지켜야 하는 것이 셋이다.
 *
 * <ol>
 *   <li><b>기록이 실제로 응답에 실린다.</b> 서버 로그에만 남기면 사용자는 여전히 모른다.
 *   <li><b>실패도 실린다.</b> 예전에는 추천 서버 장애를 {@code runTool} 이 삼키고 대화를
 *       이어 가서 화면에 아무 흔적이 없었다 — 이 저장소가 반복해서 겪은 "조용한 실패" 다.
 *   <li><b>여러 번 불린 툴을 접지 않는다.</b> {@code ai.max-tool-rounds} 가 3 이라 같은
 *       툴이 한 턴에 두 번 불릴 수 있고, <b>몇 번 불렀는지가 정보다.</b>
 * </ol>
 *
 * <p>곡 목록({@code tracks})은 반대로 <b>곡 번호로 접힌다</b> — 같은 곡이 두 줄로 보이면
 * 안 된다. 두 규칙이 서로 반대라 한 테스트에서 함께 확인한다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않는다. JDK 내장 HTTP 서버 둘(게이트웨이·추천 서버)과
 * Mockito 목으로 직접 조립한다 — {@code ChatRecommendationEndToEndTest} 와 같은 스타일이다.
 * <b>실제 게이트웨이는 부르지 않는다</b> — 키가 필요한데 테스트에 두면 안 된다.
 */
class AiChatToolCallRecordTest {

    private HttpServer gateway;
    private HttpServer recommender;
    /** 게이트웨이로 나간 요청 본문. 순서와 내용이 증거다. */
    private final List<String> gatewayRequests = new CopyOnWriteArrayList<>();
    /** 게이트웨이가 툴 호출을 돌려줄 회차. 여기까지 오면 그 뒤로는 본문을 준다. */
    private int toolCallRounds = 1;
    /** 추천 서버가 실패하게 만들 것인가. */
    private boolean recommenderDown = false;
    /** 추천 서버 호출 횟수. 재시도까지 세어진다. */
    private final AtomicInteger recommenderHits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.stop(0);
        }
        if (recommender != null) {
            recommender.stop(0);
        }
    }

    // ------------------------------------------------------------ 대역

    /**
     * 모델 대역. {@code toolCallRounds} 회까지 `recommend_music` 호출로 답하고 그 뒤 본문을 준다.
     *
     * <p>라운드마다 다른 인자를 준다 — 기록이 **호출별로** 남는지 보려면 두 호출이 달라야 한다.
     */
    private OpenAiClient gateway(boolean twoRounds) throws IOException {
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        toolCallRounds = twoRounds ? 2 : 1;
        gateway.createContext("/", exchange -> {
            gatewayRequests.add(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            int round = gatewayRequests.size();
            String body = round <= toolCallRounds
                ? toolCallResponse(round, twoRounds)
                : plainAnswer("말씀하신 결로 골라봤어요.");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        gateway.start();
        return openAiClient("http://127.0.0.1:" + gateway.getAddress().getPort());
    }

    /** 첫 라운드와 둘째 라운드의 인자를 다르게 준다. */
    private String toolCallResponse(int round, boolean twoRounds) {
        String arguments = round == 1
            ? "{\"query\":\"비 오는 날 잔잔한 곡\",\"limit\":2,"
                + "\"filters\":{\"genre\":[\"poprock\"],\"instrument\":[\"piano\"]}}"
            : "{\"query\":\"조금 더 차분하게\",\"limit\":3}";
        String id = twoRounds ? "call_" + round : "call_1";
        return """
            {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
               "role":"assistant","content":null,
               "tool_calls":[{"id":"%s","type":"function",
                 "function":{"name":"recommend_music","arguments":%s}}]}}]}
            """.formatted(id, jsonString(arguments));
    }

    private String plainAnswer(String text) {
        return """
            {"choices":[{"index":0,"finish_reason":"stop","message":{
               "role":"assistant","content":%s}}]}
            """.formatted(jsonString(text));
    }

    /** JSON 문자열 안에 넣을 값이라 따옴표·역슬래시를 이스케이프한다. */
    private String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 추천 서버 대역. {@code recommenderDown} 이면 503 을 준다 — 재시도까지 세어
     * "삼키지 않고 기록에 남기는가" 를 본다.
     */
    private RecommenderClient recommender() throws IOException {
        recommender = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        recommender.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            recommenderHits.incrementAndGet();
            String body;
            int status = 200;
            if (recommenderDown) {
                body = "{\"error_code\":\"INDEX_NOT_READY\",\"message\":\"색인이 준비되지 않았다\"}";
                status = 503;
            } else if (path.endsWith("/facets")) {
                body = FACETS;
            } else {
                body = RECOMMENDATION;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        recommender.start();
        RecommenderProperties props = new RecommenderProperties(
            "http://127.0.0.1:" + recommender.getAddress().getPort(),
            Duration.ofSeconds(2), Duration.ofSeconds(5), true, null);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new RecommenderClient(RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .build(), props);
    }

    /** 실제 `/internal/facets` 응답 모양. */
    private static final String FACETS = """
        { "catalog": { "tracks": 111, "moments": 65540 },
          "fields": {
            "genre": { "enforcement": "gate", "excludable": true,
                       "values": [ { "value": "poprock", "tracks": 42 } ] },
            "instrument": { "enforcement": "gate", "excludable": true,
                            "values": [ { "value": "piano", "tracks": 11 } ] } } }
        """;

    /** `RecommendationResponse` 계약. 숫자 ID·시간은 오지 않는다. */
    private static final String RECOMMENDATION = """
        { "recommendation_id": 9001,
          "items": [
            { "external_id": "T000901#2", "rank_order": 1, "score": 0.81,
              "reason_code": "QUERY_FILTER_MATCH", "ref_external_id": null },
            { "external_id": "T000902#1", "rank_order": 2, "score": 0.66,
              "reason_code": "QUERY_FILTER_MATCH", "ref_external_id": null } ],
          "shortfall": 0, "model_version": "mupid-stage1-v1" }
        """;

    private OpenAiClient openAiClient(String baseUrl) {
        AiProperties props = new AiProperties(baseUrl, "test-key", "gpt-5-mini",
            Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return new OpenAiClient(RestClient.builder()
            .baseUrl(props.baseUrl()).requestFactory(factory).build(), props);
    }

    private Music music(int id, String title) {
        return Music.builder()
            .musicId(id)
            .title(title)
            .artist(Artist.builder().artistId(1).name("아티스트").build())
            .album(Album.builder().albumId(1).title("앨범").build())
            .build();
    }

    private MusicSectionAnalysis section(Music music, String externalId) {
        return MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(8)
            .music(music)
            .sectionLabel(SectionLabel.CHORUS)
            .index(0)
            .startTimeMs(45_000)
            .endTimeMs(60_000)
            .externalId(externalId)
            .build();
    }

    /** 대화 저장 대역. 이 테스트의 관심사는 기록이라 저장은 곁가지다 — 세션 번호만 발급한다. */
    private AiChatHistoryService history() {
        AiChatHistoryService stub = mock(AiChatHistoryService.class);
        AiChatSession session = AiChatSession.builder().aiChatSessionId(1).build();
        when(stub.open(any(), any())).thenReturn(session);
        return stub;
    }

    private AiChatServiceImpl service(boolean twoRounds, boolean recommenderDown) throws IOException {
        this.recommenderDown = recommenderDown;
        RecommenderClient client = recommender();
        MusicRepository musicRepository = mock(MusicRepository.class);
        MusicSectionService sectionService = mock(MusicSectionService.class);
        OnboardingService onboardingService = mock(OnboardingService.class);

        Music first = music(7, "첫 번째 곡");
        Music second = music(8, "두 번째 곡");
        when(sectionService.findByExternalIds(any())).thenReturn(Map.of(
            "T000901#2", section(first, "T000901#2"),
            "T000902#1", section(second, "T000902#1")));
        when(musicRepository.findDetailAllByIds(any())).thenReturn(List.of(first, second));

        AiRecommendToolImpl tool = new AiRecommendToolImpl(
            client, musicRepository, sectionService, onboardingService,
            mock(RecommendationReasonCache.class));
        return new AiChatServiceImpl(gateway(twoRounds), tool, musicRepository,
            new AiProperties("x", "test-key", "gpt-5-mini",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20),
            history());
    }

    private AiChatResponse chat(AiChatServiceImpl service) {
        return service.chat(User.builder().userId(15).build(),
            new AiChatRequest("잔잔한 곡 추천해줘", List.of(), null, null));
    }

    // ------------------------------------------------------------ 기록

    @Test
    @DisplayName("409: 툴 호출 기록이 응답에 실린다 — 이름·인자·결과가 함께 온다")
    void toolCallIsRecordedInResponse() throws IOException {
        AiChatResponse response = chat(service(false, false));

        assertThat(response.reply()).isNotBlank();
        assertThat(response.toolCalls()).hasSize(1);

        var record = response.toolCalls().getFirst();
        // 내부 이름과 사용자용 이름이 함께 온다. 화면은 label 을 그리고, name 은 시험용 훅에 쓴다.
        assertThat(record.name()).isEqualTo("recommend_music");
        assertThat(record.label()).isEqualTo("음악 추천");
        assertThat(record.succeeded()).isTrue();
        assertThat(record.failureReason()).isNull();
        // 결과 요약 — 곡 몇 건이 돌아왔는가.
        assertThat(record.trackCount()).isEqualTo(2);
        // 모델이 넘긴 인자. "왜 이 곡이 나왔나" 의 근거다.
        assertThat(record.arguments()).containsEntry("query", "비 오는 날 잔잔한 곡");
        assertThat(record.arguments()).containsEntry("limit", 2);
        assertThat(record.arguments().get("filters").toString()).contains("poprock");
    }

    @Test
    @DisplayName("409: 추천 서버가 죽어도 대화는 이어지고, 실패가 기록으로 남는다 — 조용히 삼키지 않는다")
    void failureIsRecordedNotSwallowed() throws IOException {
        AiChatResponse response = chat(service(false, true));

        // 대화는 살아 있다. 실패를 이유로 502 를 내면 사용자는 빈 화면만 본다.
        assertThat(response.reply()).isNotBlank();
        assertThat(response.tracks()).isEmpty();

        assertThat(response.toolCalls()).hasSize(1);
        var record = response.toolCalls().getFirst();
        assertThat(record.succeeded()).isFalse();
        assertThat(record.trackCount()).isZero();
        // **사유가 문장으로 온다.** 화면이 "도구 호출 → 실패(추천 서버가 응답하지 않습니다.)"
        // 를 그릴 수 있어야 사용자가 상황을 안다.
        assertThat(record.failureReason()).isNotBlank();
        assertThat(record.failureReason()).contains("응답하지 않습니다");
        // 인자는 실패해도 남는다 — 무엇을 찾으려다 실패했는지가 정보다.
        assertThat(record.arguments()).containsEntry("query", "비 오는 날 잔잔한 곡");
    }

    @Test
    @DisplayName("409: 같은 툴이 두 번 불리면 기록은 둘, 곡 목록은 접힌다 — 몇 번 불렀는지가 정보다")
    void repeatedCallsAreNotCollapsed() throws IOException {
        AiChatResponse response = chat(service(true, false));

        // 기록은 **호출 순서대로 전부**. 라운드마다 인자가 다르므로 구분된다.
        assertThat(response.toolCalls()).hasSize(2);
        assertThat(response.toolCalls().get(0).arguments())
            .containsEntry("query", "비 오는 날 잔잔한 곡");
        assertThat(response.toolCalls().get(1).arguments())
            .containsEntry("query", "조금 더 차분하게");
        // 결과 요약도 호출마다 따로다.
        assertThat(response.toolCalls()).allMatch(r -> r.trackCount() == 2);

        // **곡 목록은 반대로 접힌다.** 추천 서버가 같은 구간을 두 번 줬으므로 곡은 2건이다.
        // 접지 않으면 화면에 같은 곡이 네 줄로 보인다.
        assertThat(response.tracks()).hasSize(2);

        // 게이트웨이는 **툴 호출 횟수만큼** 왕복한다: 두 번 불렀으면 요청 3회다
        // (1회차 툴 호출 → 2회차 툴 호출 → 3회차 본문). 로그에 남는 것은 이 3건이다.
        assertThat(gatewayRequests).hasSize(3);
    }

    @Test
    @DisplayName("409: 툴을 안 부른 턴에는 기록이 비어 있다 — 화면이 아무것도 그리지 않는다")
    void noToolCallMeansEmptyRecord() throws IOException {
        // 첫 요청에 본문만 주도록 만든다 — 툴 호출이 아예 없던 턴이다.
        AiChatServiceImpl service = serviceWithoutToolCall();

        AiChatResponse response = chat(service);

        assertThat(response.reply()).isNotBlank();
        // null 이 아니라 빈 배열이어야 화면이 분기 없이 `length > 0` 으로 판단할 수 있다.
        assertThat(response.toolCalls()).isNotNull().isEmpty();
    }

    /** 게이트웨이가 처음부터 본문만 주는 서비스. */
    private AiChatServiceImpl serviceWithoutToolCall() throws IOException {
        HttpServer plain = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        plain.createContext("/", exchange -> {
            gatewayRequests.add(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            byte[] out = plainAnswer("네, 그렇게 들려드릴게요.").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        plain.start();
        if (gateway != null) {
            gateway.stop(0);
        }
        gateway = plain;

        RecommenderClient client = recommender();
        MusicRepository musicRepository = mock(MusicRepository.class);
        AiRecommendToolImpl tool = new AiRecommendToolImpl(
            client, musicRepository, mock(MusicSectionService.class),
            mock(OnboardingService.class), mock(RecommendationReasonCache.class));
        return new AiChatServiceImpl(openAiClient("http://127.0.0.1:" + plain.getAddress().getPort()),
            tool, musicRepository,
            new AiProperties("x", "test-key", "gpt-5-mini",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20),
            history());
    }

    @Test
    @DisplayName("409: 모델에게 돌려주는 tool 메시지 계약은 그대로다 — 기록을 모으느라 깨지 않았다")
    void toolMessageContractUnchanged() throws IOException {
        chat(service(false, false));

        // 두 번째 요청에 우리가 실행한 결과가 tool 메시지로 붙는다. 게이트웨이가 요구하는
        // 계약이라 여기 모양이 바뀌면 툴 왕복 자체가 깨진다.
        String second = gatewayRequests.get(1);
        assertThat(second).contains("\"role\":\"tool\"");
        // 번역이 끝난 곡이 간다 — 문자열 ID 가 아니라 숫자 ID·제목이다.
        assertThat(second).contains("\\\"music_id\\\":7");
        assertThat(second).contains("첫 번째 곡");
        // 선언한 툴 이름은 그대로 노출된다(모델이 보는 이름이다).
        assertThat(gatewayRequests.getFirst()).contains("\"name\":\"recommend_music\"");
    }

    @Test
    @DisplayName("409: 기록의 인자에서 빈 껍데기를 걷어낸다 — 화면에 빈 줄을 남기지 않는다")
    void blankArgumentsAreDroppedButQueryKept() throws IOException {
        // 모델은 조건이 없을 때도 `{"genre": []}` 같은 빈 껍데기를 곧잘 만든다.
        AiChatServiceImpl service = serviceWithArguments(
            "{\"query\":\"아무거나\",\"filters\":{\"genre\":[]},\"exclude\":{\"mood\":[]}}");

        AiChatResponse response = chat(service);

        var record = response.toolCalls().getFirst();
        assertThat(record.arguments()).containsOnlyKeys("query");
        assertThat(record.arguments()).containsEntry("query", "아무거나");
    }

    /** 인자를 고정한 서비스. 빈 껍데기 검증에만 쓴다. */
    private AiChatServiceImpl serviceWithArguments(String arguments) throws IOException {
        HttpServer fixed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixed.createContext("/", exchange -> {
            gatewayRequests.add(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            String body = gatewayRequests.size() == 1
                ? """
                  {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
                     "role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":"recommend_music","arguments":%s}}]}}]}
                  """.formatted(jsonString(arguments))
                : plainAnswer("골라봤어요.");
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        fixed.start();
        if (gateway != null) {
            gateway.stop(0);
        }
        gateway = fixed;

        RecommenderClient client = recommender();
        MusicRepository musicRepository = mock(MusicRepository.class);
        MusicSectionService sectionService = mock(MusicSectionService.class);
        Music first = music(7, "첫 번째 곡");
        when(sectionService.findByExternalIds(any())).thenReturn(Map.of(
            "T000901#2", section(first, "T000901#2"),
            "T000902#1", section(first, "T000902#1")));
        when(musicRepository.findDetailAllByIds(any())).thenReturn(List.of(first));

        AiRecommendToolImpl tool = new AiRecommendToolImpl(
            client, musicRepository, sectionService, mock(OnboardingService.class),
            mock(RecommendationReasonCache.class));
        return new AiChatServiceImpl(
            openAiClient("http://127.0.0.1:" + fixed.getAddress().getPort()),
            tool, musicRepository,
            new AiProperties("x", "test-key", "gpt-5-mini",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20),
            history());
    }

}
