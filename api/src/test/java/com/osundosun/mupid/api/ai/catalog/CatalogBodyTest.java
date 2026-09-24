package com.osundosun.mupid.api.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.client.AiJson;

/**
 * 요청 본문의 {@code catalog} 는 받는 쪽({@code ai_server/app/catalog_body.py})과의 계약이다.
 * 이름·모양이 틀리면 추천이 통째로 400 이다.
 */
class CatalogBodyTest {

    private static RecommenderCatalog catalog() {
        return new RecommenderCatalog(
            List.of(
                new RecommenderCatalog.Track("T1", 1, 150_000, 120.5, 115, 125, "korean",
                    "female_korean_ballad", List.of("pop"), List.of("calm"), true, "F"),
                // 모르는 값이 섞인 곡. null 이 배열에서 빠지면 뒤 행이 한 칸씩 밀린다.
                new RecommenderCatalog.Track("T2", 2, null, null, null, null, null, null,
                    List.of(), List.of(), null, null),
                new RecommenderCatalog.Track("T3", 3, 90_000, 80.0, null, null, "english",
                    "male_english_pop", List.of("pop"), List.of(), true, "M")),
            List.of(
                new RecommenderCatalog.Section("T1#0", "T1", "INTRO", 0, 15_500, 0.031, List.of("piano")),
                new RecommenderCatalog.Section("T2#0", "T2", "CHORUS", 0, 30_000, null, List.of())),
            Instant.now());
    }

    @Test
    @DisplayName("열 이름은 snake_case 이고, 배열 안의 null 은 빠지지 않는다")
    void serializesColumnsWithNullsKept() {
        String json = new String(AiJson.writeBytes(catalog().body()), StandardCharsets.UTF_8);

        assertThat(json).contains("\"duration_ms\":[150000,null]");
        assertThat(json).contains("\"bpm\":[120.5,null]");
        assertThat(json).contains("\"has_vocal\":[true,null]");
        assertThat(json).contains("\"external_id\":[\"T1#0\",\"T2#0\"]");
        assertThat(json).contains("\"energy\":[0.031,null]");
        assertThat(json).contains("\"bpm_requested_min\":[115,null]");
        // 악기는 싣지 않는다 — 조건은 여기서 걸었다.
        assertThat(json).doesNotContain("instruments").doesNotContain("piano");
    }

    @Test
    @DisplayName("구간이 없는 곡은 싣지 않는다")
    void tracksWithoutSectionsAreLeftOut() {
        CatalogBody body = catalog().body();

        assertThat(body.tracks().track()).containsExactly("T1", "T2");
        assertThat(body.sectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("조건으로 거르면 남은 구간의 곡만 싣는다")
    void filteredBodyCarriesOnlyWhatIsLeft() {
        CatalogBody body = catalog().body(s -> s.externalId().equals("T1#0"));

        assertThat(body.tracks().track()).containsExactly("T1");
        assertThat(body.sections().externalId()).containsExactly("T1#0");
    }

}
