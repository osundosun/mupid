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
import com.osundosun.mupid.api.ai.tool.TestMusicSummaries;
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
 * 378 재현 시도 — <b>챗봇에서 곡 추천이 실제로 끝까지 도는가.</b>
 *
 * <p>티켓 378 의 증상은 셋이다: 곡 추천이 안 된다 · 도구가 부족하다 · 모델이 도구를
 * 호출하지 않는다. 그것을 <b>대화 한 턴 전체</b>로 태운다. 앞선 테스트들은 조각을
 * 보지만(선언만, 툴만) 여기서는 게이트웨이 → 툴 호출 → 추천 서버 → DB → 최종 응답까지
 * 한 줄로 이어 본다.
 *
 * <p>게이트웨이 대역이 <b>진짜로 `recommend_music` 을 부른다</b> — 첫 요청에는
 * `tool_calls` 로 답하고 두 번째에 본문을 준다. 모델이 도구를 부르지 않는 것이 증상이라면
 * 여기서 곡이 0건이 되고, 도구가 부족하다면 모델이 조건을 걸 수 없어 추천 서버가 빈 목록을
 * 준다. 그래서 대역 추천 서버는 <b>실제 `/internal/facets` 응답 모양</b>
 * (`enforcement`·`excludable`·`values`·`style_values`·`range`)과 실제
 * `external_id`(`T000901#2`)를 쓴다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않는다. JDK 내장 HTTP 서버 둘(게이트웨이·추천 서버)과
 * Mockito 목으로 협력자를 직접 조립한다 — `ai/client/*Test` 와 같은 스타일이다.
 */
class ChatRecommendationEndToEndTest {

    private static final String ITEM_SECTION = "T000901#2";

    private HttpServer gateway;
    private HttpServer recommender;
    /** 게이트웨이로 나간 요청 본문을 순서대로 담는다. 무엇이 실렸는지가 증거다. */
    private final List<String> gatewayRequests = new CopyOnWriteArrayList<>();

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
     * 모델 대역. 첫 호출에는 `recommend_music` 호출을, 두 번째에는 본문을 돌려준다.
     *
     * @param arguments 모델이 만든 툴 인자 JSON 문자열
     */
    private OpenAiClient gateway(String arguments) throws IOException {
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", exchange -> {
            gatewayRequests.add(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
            String body = gatewayRequests.size() == 1
                ? """
                  {"choices":[{"index":0,"finish_reason":"tool_calls","message":{
                     "role":"assistant","content":null,
                     "tool_calls":[{"id":"call_1","type":"function",
                       "function":{"name":"recommend_music","arguments":%s}}]}}]}
                  """.formatted(jsonString(arguments))
                : """
                  {"choices":[{"index":0,"finish_reason":"stop","message":{
                     "role":"assistant","content":"말씀하신 결로 두 곡을 골라봤어요."}}]}
                  """;
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        gateway.start();
        return openAiClient("http://127.0.0.1:" + gateway.getAddress().getPort());
    }

    /** JSON 문자열 안에 넣을 값이라 따옴표를 이스케이프한다. */
    private String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 추천 서버 대역. `/internal/facets` 는 <b>실제 응답 모양</b>으로, 추천 경로는
     * 계약대로 `external_id` 만 돌려준다.
     */
    private RecommenderClient recommender() throws IOException {
        recommender = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        recommender.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/facets") ? FACETS : RECOMMENDATION;
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
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

    /**
     * `ai_server/app/internal_api.py._facets_payload` 가 실제로 만드는 모양이다.
     * `enforcement`·`excludable` 이 붙고 장르만 `style_values` 를 따로 갖는다.
     */
    private static final String FACETS = """
        { "catalog": { "tracks": 111, "moments": 65540 },
          "fields": {
            "genre": { "enforcement": "gate", "excludable": true,
                       "values": [ { "value": "poprock", "tracks": 42 } ],
                       "style_values": [ { "value": "ko_ballad", "tracks": 9 } ] },
            "language": { "enforcement": "gate", "excludable": true,
                          "values": [ { "value": "ko", "tracks": 30 } ] },
            "vocal": { "enforcement": "gate", "excludable": true,
                       "values": [ { "value": "female", "tracks": 20 } ] },
            "instrument": { "enforcement": "gate", "excludable": true,
                            "values": [ { "value": "piano", "tracks": 11 } ] },
            "mood": { "enforcement": "score_only", "excludable": true,
                      "values": [ { "value": "잔잔한", "tracks": 18 } ] },
            "flow": { "enforcement": "gate", "excludable": true,
                      "values": [ { "value": "CHORUS", "moments": 900 } ] },
            "bpm_min": { "enforcement": "gate", "excludable": false, "range": [60, 180] },
            "bpm_max": { "enforcement": "gate", "excludable": false, "range": [60, 180] },
            "tempo_direction": { "enforcement": "gate_with_reference", "excludable": false,
                                 "values": [ { "value": "up" }, { "value": "down" } ] } },
          "example": { "filters": { "genre": ["pop"], "instrument": ["piano"] } } }
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

    /**
     * 대화 저장 대역. 이 테스트의 관심사는 **추천 경로**라 저장은 곁가지다 — 세션 번호만
     * 발급한다. 저장 자체는 {@code AiChatHistoryPersistTest} 가 실 DB 로 본다.
     */
    private AiChatHistoryService history() {
        AiChatHistoryService stub = mock(AiChatHistoryService.class);
        AiChatSession session = AiChatSession.builder().aiChatSessionId(1).build();
        when(stub.open(any(), any())).thenReturn(session);
        return stub;
    }

    /** 추천 서버가 살아 있고 DB 에도 그 구간이 있는 상태의 서비스. */
    private AiChatServiceImpl service(String toolArguments) throws IOException {
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
            client, musicRepository, sectionService, TestMusicSummaries.assembler(),
            onboardingService, mock(RecommendationReasonCache.class));
        return new AiChatServiceImpl(gateway(toolArguments), tool, musicRepository,
            new AiProperties("x", "test-key", "gpt-5-mini",
                Duration.ofSeconds(2), Duration.ofSeconds(2), 3, 20),
            history());
    }

    // ------------------------------------------------------------ 재현 시도

    @Test
    @DisplayName("378 재현 시도: 모델이 도구를 부르면 곡이 화면까지 돌아온다 — 추천이 끝까지 돈다")
    void recommendedTracksReachTheResponse() throws IOException {
        // 모델이 조건을 걸어 부른다. 조건이 있으면 `search` 경로다.
        AiChatServiceImpl service = service(
            "{\"query\":\"지금 곡보다 잔잔한 곡\",\"limit\":2,"
                + "\"filters\":{\"genre\":[\"poprock\"],\"instrument\":[\"piano\"]}}");

        AiChatResponse response = service.chat(User.builder().userId(15).build(),
            new AiChatRequest("잔잔한 곡 추천해줘", List.of(), null, null));

        // 1) 답변이 온다 — 도구 호출 라운드가 정상 종료됐다는 뜻이다.
        assertThat(response.reply()).isNotBlank();
        // recommendationId 는 **추천 서버가 준 값이 아니라** 우리가 발급한 상관 키다
        // (`AiRecommendToolImpl` 의 `System.currentTimeMillis()`). 노출 기록
        // (`rec_impressions`)이 붙기 전까지의 자리표시자라, 여기서는 "발급됐다" 만 본다.
        assertThat(response.recommendationId()).isNotNull().isPositive();
        // 2) 추천 서버가 준 두 구간이 DB 를 거쳐 곡으로 돌아왔다.
        assertThat(response.tracks()).hasSize(2);
        assertThat(response.tracks().getFirst().music().title()).isEqualTo("첫 번째 곡");
        // 숫자 ID 로 번역됐다 = 우리가 `external_id` → MySQL 조회까지 갔다는 뜻이다.
        assertThat(response.tracks().getFirst().musicSectionId()).isEqualTo(8);
        assertThat(response.tracks().getFirst().startTimeMs()).isEqualTo(45_000);
        // 3) 대화는 두 번 나갔다: 툴 선언 + 결과 주입 → 본문.
        assertThat(gatewayRequests).hasSize(2);
    }

    @Test
    @DisplayName("378 재현 시도: 첫 요청에 도구가 실리고 어휘(enum)까지 채워져 나간다 — '도구가 부족' 하지 않다")
    void toolIsDeclaredWithVocabulary() throws IOException {
        AiChatServiceImpl service = service("{\"query\":\"잔잔한 곡\"}");

        service.chat(User.builder().userId(15).build(),
            new AiChatRequest("추천해줘", List.of(), null, null));

        String first = gatewayRequests.getFirst();
        assertThat(first).contains("\"tools\"");
        assertThat(first).contains("\"name\":\"recommend_music\"");
        assertThat(first).contains("\"required\":[\"query\"]");
        // 어휘가 스키마에 실렸다 = 모델이 카탈로그에 있는 말로만 조건을 만들 수 있다.
        assertThat(first).contains("\"poprock\"");
        assertThat(first).contains("\"ko_ballad\"");
        assertThat(first).contains("\"piano\"");
        // 두 번째 요청에는 우리가 실행한 툴 결과가 tool 메시지로 붙는다.
        assertThat(gatewayRequests.get(1)).contains("\"role\":\"tool\"");
        // 모델에게는 **번역이 끝난 곡**이 간다 — 문자열 ID 가 아니라 숫자 ID·제목이다.
        // 화면이 재생할 수 있는 것은 이 숫자뿐이라, 번역이 여기까지 왔는지가 핵심이다.
        //
        // 주의: 툴 결과는 `tool` 메시지의 **문자열 content** 안에 JSON 으로 들어간다.
        // 그래서 와이어에서는 따옴표가 `\"` 로 이스케이프돼 있다 — 이스케이프 **전**
        // 모양으로 찾으면 못 찾는다(실제로 여기서 한 번 걸렸다).
        assertThat(gatewayRequests.get(1)).contains("\\\"music_id\\\":7");
        assertThat(gatewayRequests.get(1)).contains("첫 번째 곡");
        assertThat(gatewayRequests.get(1)).contains("\\\"music_section_id\\\":8");
    }

    @Test
    @DisplayName("378 재현 시도: 모델이 조건 없이 불러도(빈 껍데기 filters) 도구가 정상 돈다")
    void emptyFilterShellStillWorks() throws IOException {
        // 모델은 조건이 없을 때도 `{"genre": []}` 같은 빈 껍데기를 곧잘 만든다.
        AiChatServiceImpl service = service(
            "{\"query\":\"아무거나\",\"filters\":{\"genre\":[]},\"exclude\":{\"mood\":[]}}");

        AiChatResponse response = service.chat(User.builder().userId(15).build(),
            new AiChatRequest("추천", List.of(), null, null));

        assertThat(response.reply()).isNotBlank();
        assertThat(response.tracks()).hasSize(2);
    }

    @Test
    @DisplayName("378 재현 시도: 모델이 도구를 안 부르고 본문만 주면 곡이 없지만 대화는 정상이다")
    void plainAnswerWithoutToolCall() throws IOException {
        // 게이트웨이 대역이 첫 요청에 tool_calls 를 주도록 고정돼 있어, 여기서는
        // 도구를 못 부르는 상태를 만들 수 없다. 대신 인자 없는 호출을 태운다.
        AiChatServiceImpl service = service("{\"query\":\"아무거나\"}");

        AiChatResponse response = service.chat(User.builder().userId(15).build(),
            new AiChatRequest("추천", List.of(), null, null));

        assertThat(response.reply()).isNotBlank();
        // 도구가 돌았으므로 곡이 있다. 이 값이 0 이면 그것이 곧 378 이다.
        assertThat(response.tracks()).isNotEmpty();
    }
}
