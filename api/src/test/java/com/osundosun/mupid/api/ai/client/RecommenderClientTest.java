package com.osundosun.mupid.api.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;

/**
 * 추천 서버가 어떻게 실패하느냐에 따라 우리가 다르게 굴어야 한다. 그 분기를 본다.
 *
 * MockWebServer 를 새로 들이지 않고 JDK 내장 HTTP 서버를 쓴다. 여기서 확인할 것은
 * "상태 코드와 error_code 를 보고 재시도할지 정한다" 뿐이라 그 이상이 필요 없다.
 *
 * 특히 404 가 두 가지라는 점이 핵심이다. `NO_CANDIDATES` 는 **장애가 아니다** — 조건에
 * 맞는 곡이 없다는 정상 결과이므로 재시도하면 사용자만 기다리게 된다.
 */
class RecommenderClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** @param bodies 호출 순서대로 돌려줄 (상태, 본문). 모자라면 마지막 것을 반복한다 */
    private RecommenderClient start(AtomicInteger calls, List<Map.Entry<Integer, String>> bodies)
        throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int i = Math.min(calls.getAndIncrement(), bodies.size() - 1);
            byte[] body = bodies.get(i).getValue().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(bodies.get(i).getKey(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return client(new RecommenderProperties(
            baseUrl, Duration.ofSeconds(2), Duration.ofSeconds(2), true, null));
    }

    /** 받은 X-Internal-Token 을 기록하는 서버. 값 자체가 아니라 실렸는지를 본다. */
    private RecommenderClient startCapturing(List<String> seen, String token) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String v = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            seen.add(v == null ? "(없음)" : v);
            byte[] body = """
                { "recommendation_id": 1, "shortfall": 0, "model_version": "v", "items": [] }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return client(new RecommenderProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            Duration.ofSeconds(2), Duration.ofSeconds(2), true, token));
    }

    private RecommenderClient client(RecommenderProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        RestClient rest = RestClient.builder()
            .baseUrl(props.isConfigured() ? props.baseUrl() : "http://recommender.invalid")
            .requestFactory(factory)
            .build();
        return new RecommenderClient(rest, props);
    }

    private RecommenderDto.SearchRequest request() {
        return new RecommenderDto.SearchRequest(1L, 15, 3, List.of(), null,
            Map.of("filters", Map.of("genre", List.of("pop"))), null);
    }

    @Test
    @DisplayName("성공하면 구간 목록을 돌려준다")
    void success() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(200, """
            { "recommendation_id": 1, "shortfall": 0, "model_version": "v",
              "items": [ { "external_id": "T000901#2", "rank_order": 1, "score": 1.0,
                "reason_code": "COLD_START" } ] }
            """)));

        List<RecommenderDto.Item> items = client.search(request());

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().externalId()).isEqualTo("T000901#2");
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("NO_CANDIDATES 는 장애가 아니라 빈 목록이다 — 재시도하지 않는다")
    void noCandidates() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(404,
            "{ \"error_code\": \"NO_CANDIDATES\", \"message\": \"해당되는 노래가 없습니다.\" }")));

        assertThat(client.search(request())).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("UNSATISFIABLE_CONDITIONS 도 장애가 아니라 빈 목록이다 — 조건을 풀지 않고, 재시도하지 않는다")
    void unsatisfiableConditions() throws IOException {
        // 계약 v3: 조건은 추천 서버가 건다. 어휘에 없는 필수 조건이면 404 로 "맞을 수 없다" 고 답한다.
        // 예전 QueryPlanFilter 의 "만족 불가" 와 같은 뜻이라 대화는 "맞는 곡이 없다" 로 이어져야 한다.
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(404,
            "{ \"error_code\": \"UNSATISFIABLE_CONDITIONS\", \"message\": \"instrument=theremin 은 어휘에 없다\" }")));

        assertThat(client.search(request())).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("CONDITIONS_UNAVAILABLE(503) 은 다른 5xx 처럼 한 번만 다시 부르고 올린다")
    void conditionsUnavailableRetriesOnce() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(503,
            "{ \"error_code\": \"CONDITIONS_UNAVAILABLE\", \"message\": \"feature store unreachable\" }")));

        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(AiErrorCode.RECOMMENDER_UNAVAILABLE);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("503 은 한 번만 다시 부른다")
    void retriesOnceOnUnavailable() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(503,
            "{ \"error_code\": \"INDEX_NOT_READY\", \"message\": \"색인 미완\" }")));

        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(AiErrorCode.RECOMMENDER_UNAVAILABLE);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("503 뒤에 성공하면 그 결과를 쓴다")
    void recoversOnRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(
            Map.entry(503, "{ \"error_code\": \"INDEX_NOT_READY\", \"message\": \"\" }"),
            Map.entry(200, """
                { "recommendation_id": 1, "shortfall": 0, "model_version": "v",
                  "items": [ { "music_id": 7, "music_section_id": 8, "start_time_ms": 0,
                    "end_time_ms": 1, "section_label": "VERSE", "rank_order": 1,
                    "score": 0.5, "reason_code": "COLD_START", "reason": "x" } ] }
                """)));

        assertThat(client.search(request())).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    @DisplayName("400 은 다시 불러도 같은 답이라 재시도하지 않는다")
    void doesNotRetryBadRequest() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(400,
            "{ \"error_code\": \"INVALID_REQUEST\", \"message\": \"limit: 범위를 벗어남\" }")));

        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(AiErrorCode.RECOMMENDER_REQUEST_FAILED);
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("501 은 우리 쪽 실수다 — 재시도하지 않는다")
    void doesNotRetryNotImplemented() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(501,
            "{ \"error_code\": \"SEARCH_NOT_CONFIGURED\", \"message\": \"해석기 없음\" }")));

        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class);
        // query_plan 을 보내는 한 여기 걸릴 일이 없다. 걸렸다면 원문을 보낸 것이므로
        // 다시 불러도 같다.
        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("주소가 없으면 호출하지 않고 설정 오류로 답한다")
    void notConfigured() {
        RecommenderClient client = client(new RecommenderProperties(
            "  ", Duration.ofSeconds(1), Duration.ofSeconds(1), true, null));

        assertThat(client.isConfigured()).isFalse();
        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(AiErrorCode.RECOMMENDER_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("공유 비밀이 있으면 X-Internal-Token 으로 실어 보낸다")
    void sendsInternalToken() throws IOException {
        List<String> seen = new java.util.ArrayList<>();
        RecommenderClient client = startCapturing(seen, "s3cret");

        client.search(request());

        assertThat(seen).containsExactly("s3cret");
    }

    @Test
    @DisplayName("공유 비밀이 없으면 헤더를 아예 보내지 않는다")
    void omitsHeaderWhenNotSet() throws IOException {
        List<String> seen = new java.util.ArrayList<>();
        RecommenderClient client = startCapturing(seen, "  ");

        client.search(request());

        assertThat(seen).containsExactly("(없음)");
    }

    @Test
    @DisplayName("401 은 한쪽만 켠 상태다 — 재시도하지 않는다")
    void doesNotRetryUnauthorized() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        RecommenderClient client = start(calls, List.of(Map.entry(401,
            "{ \"error_code\": \"UNAUTHORIZED\", \"message\": \"내부 호출 토큰이 올바르지 않습니다.\" }")));

        assertThatThrownBy(() -> client.search(request()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(AiErrorCode.RECOMMENDER_REQUEST_FAILED);
        assertThat(calls).hasValue(1);
    }
}
