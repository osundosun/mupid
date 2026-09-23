package com.osundosun.mupid.api.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.catalog.RecommenderCatalog;
import com.osundosun.mupid.api.ai.catalog.RecommenderCatalogService;
import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * 툴 선언은 **어떤 실패에도** 만들어진다.
 *
 * <p>대화의 첫 문장이 툴 선언이다. 여기서 예외가 새면 LLM 을 부르지도 못한 채 대화가 통째로
 * 실패하는데, 사용자 눈에는 정확히 "도구 호출이 안 되는" 상태로 보인다.
 *
 * <p>조건 어휘는 이제 **MySQL 후보에서** 만든다({@code RecommenderFacets}, 연동 계약
 * 2026-09-23 절). 예전에는 추천 서버의 {@code GET /internal/facets} 에 매달려 있어서, 추천
 * 서버가 죽으면 대화마다 연결 타임아웃을 기다렸다. 그래서 여기서는 두 가지를 본다 —
 * 선언이 추천 서버를 부르지 않는다는 것, 후보를 못 읽어도 선언은 나온다는 것.
 */
class AiRecommendToolDeclarationTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 부르면 세는 추천 서버 대역. 선언 경로에서는 한 번도 불리면 안 된다. */
    private RecommenderClient countingRecommender(AtomicInteger calls) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        RecommenderProperties props = new RecommenderProperties(
            "http://127.0.0.1:" + server.getAddress().getPort(),
            Duration.ofSeconds(2), Duration.ofSeconds(2), true, null);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        return new RecommenderClient(RestClient.builder().baseUrl(props.baseUrl())
            .requestFactory(factory).build(), props);
    }

    private static RecommenderCatalog catalog() {
        return new RecommenderCatalog(
            List.of(
                track("T1", List.of("pop"), "female_korean_ballad", true, "F"),
                track("T2", List.of("pop"), "instrumental_lofi_hiphop", false, null),
                track("T3", List.of("rock"), "male_english_rock", true, "M")),
            List.of(
                section("T1#0", "T1", "CHORUS"), section("T2#0", "T2", "VERSE"),
                section("T3#0", "T3", "CHORUS")),
            Instant.now());
    }

    private static RecommenderCatalog.Track track(String id, List<String> genres, String category,
                                                  Boolean hasVocal, String gender) {
        return new RecommenderCatalog.Track(id, 1, 150_000, 100.0, null, null,
            category.contains("korean") ? "korean" : category.contains("english") ? "english" : null,
            category, genres, List.of("calm"), hasVocal, gender);
    }

    private static RecommenderCatalog.Section section(String uid, String track, String label) {
        return new RecommenderCatalog.Section(uid, track, label, 0, 20_000, 0.05, List.of());
    }

    private AiRecommendToolImpl tool(RecommenderClient recommender, RecommenderCatalogService catalogs) {
        return new AiRecommendToolImpl(recommender, catalogs, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(AiRecommendToolImpl tool) {
        return (Map<String, Object>) tool.declaration().function().parameters().get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> condition(AiRecommendToolImpl tool, String slot) {
        Map<String, Object> spec = (Map<String, Object>) properties(tool).get(slot);
        return (Map<String, Object>) spec.get("properties");
    }

    @Test
    @DisplayName("선언은 추천 서버를 부르지 않는다 — 추천 서버가 죽어도 대화가 기다리지 않는다")
    void declarationNeverCallsTheRecommender() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AiRecommendToolImpl tool = tool(countingRecommender(calls), () -> catalog());

        OpenAiTool declaration = tool.declaration();

        assertThat(declaration.function().name()).isEqualTo(AiRecommendTool.NAME);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("후보를 못 읽어도(DB 장애) 선언은 나온다 — 조건 칸은 남고 enum 만 빠진다")
    void declaresWhenTheCatalogCannotBeRead() {
        AiRecommendToolImpl tool = tool(null, () -> {
            throw new IllegalStateException("MySQL 연결 실패");
        });

        assertThatCode(tool::declaration).doesNotThrowAnyException();
        @SuppressWarnings("unchecked")
        Map<String, Object> genre = (Map<String, Object>) condition(tool, "filters").get("genre");
        assertThat(genre).containsEntry("type", "array");
        assertThat(genre).doesNotContainKey("enum");
        assertThat(condition(tool, "filters")).containsKeys(
            "genre", "language", "vocal", "instrument", "mood", "flow");
        assertThat(properties(tool)).containsKeys("query", "limit", "filters", "exclude");
    }

    @Test
    @DisplayName("후보가 비어 있어도 선언은 나온다")
    void declaresWithAnEmptyCatalog() {
        AiRecommendToolImpl tool = tool(null, RecommenderCatalog::empty);

        assertThatCode(tool::declaration).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("후보의 어휘로 enum 이 채워진다 — 장르 칸은 장르 코드와 스타일을 함께 연다")
    @SuppressWarnings("unchecked")
    void fillsEnumFromTheCatalog() {
        AiRecommendToolImpl tool = tool(null, () -> catalog());

        Map<String, Object> genre = (Map<String, Object>) condition(tool, "filters").get("genre");
        List<String> values = (List<String>) ((Map<String, Object>) genre.get("items")).get("enum");

        // 많은 순. pop(2) · rock(1), 그다음 스타일 — 보컬·언어 접두어를 뗀 것이다.
        assertThat(values).containsExactly("pop", "rock", "ballad", "lofi_hiphop");
        Map<String, Object> vocal = (Map<String, Object>) condition(tool, "filters").get("vocal");
        assertThat((List<String>) ((Map<String, Object>) vocal.get("items")).get("enum"))
            .contains("voice", "female", "male", "instrumental");
    }

    @Test
    @DisplayName("어휘는 선언 하나당 한 번만 만든다 — filters·exclude 가 따로 읽지 않는다")
    void buildsVocabularyOncePerDeclaration() {
        AtomicInteger reads = new AtomicInteger();
        AiRecommendToolImpl tool = tool(null, () -> {
            reads.incrementAndGet();
            return catalog();
        });

        tool.declaration();

        assertThat(reads).hasValue(1);
    }
}
