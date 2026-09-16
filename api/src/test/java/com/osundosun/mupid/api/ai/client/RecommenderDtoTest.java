package com.osundosun.mupid.api.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.tool.RecommendReason;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 추천 서버와의 **와이어 포맷**만 본다. 스프링 컨텍스트도 DB 도 필요 없다.
 *
 * 이 형식이 어긋나면 눈에 띄게 실패하지 않는다. 필드 이름 하나가 틀리면 추천 서버는
 * `extra = forbid` 라 400 을 내거나(요청), 우리 쪽에서 값이 0·null 로 들어온다(응답).
 * 둘 다 "추천이 좀 이상하다" 로만 보이므로 테스트로 못박는다.
 */
class RecommenderDtoTest {

    @Test
    @DisplayName("검색 요청은 snake_case 로 나가고 비어 있는 칸은 빠진다")
    void writeSearchRequest() {
        RecommenderDto.SearchRequest request = new RecommenderDto.SearchRequest(
            5001L, 15, 3, List.of("T000901"), null,
            Map.of("filters", Map.of("genre", List.of("pop"))),
            new RecommenderDto.Onboarding(List.of("잔잔한"), List.of("Jazz")));

        String json = new String(AiJson.writeBytes(request), StandardCharsets.UTF_8);

        assertThat(json).contains("\"recommendation_id\":5001");
        assertThat(json).contains("\"user_id\":15");
        assertThat(json).contains("\"exclude_tracks\":[\"T000901\"]");
        // 온보딩은 **우리 코드표 이름 그대로** 나간다. 옮기는 것은 저쪽 몫이다.
        assertThat(json).contains("\"moods\":[\"잔잔한\"]");
        assertThat(json).contains("\"query_plan\"");
        // 추천 서버는 `extra = forbid` 다. 안 쓰는 칸을 null 로라도 보내면 400 이 아니라
        // 값이 들어간 것으로 읽힐 수 있어, 아예 빠지는 편이 안전하다.
        assertThat(json).doesNotContain("\"query\"");
    }

    @Test
    @DisplayName("이어 듣기 요청도 같은 규칙을 따른다")
    void writeNextRequest() {
        RecommenderDto.NextRequest request =
            new RecommenderDto.NextRequest(7L, 15, 5, List.of(), "T000901", null, null);

        String json = new String(AiJson.writeBytes(request), StandardCharsets.UTF_8);

        assertThat(json).contains("\"current_track\":\"T000901\"");
        assertThat(json).doesNotContain("current_section");
        assertThat(json).doesNotContain("onboarding");
    }

    @Test
    @DisplayName("응답은 문자열 ID·순위·사유뿐이다")
    void readResponse() {
        String body = """
            { "recommendation_id": 5001,
              "items": [
                { "external_id": "T000901#2", "rank_order": 1, "score": 1.0,
                  "reason_code": "SIMILAR_PREFERRED_MOMENT",
                  "ref_external_id": "T000148#3" }
              ],
              "shortfall": 0, "model_version": "mupid-stage1-v1" }
            """;

        RecommenderDto.Response response =
            AiJson.read(body.getBytes(StandardCharsets.UTF_8), RecommenderDto.Response.class);

        assertThat(response.recommendationId()).isEqualTo(5001L);
        assertThat(response.modelVersion()).isEqualTo("mupid-stage1-v1");
        RecommenderDto.Item item = response.safeItems().getFirst();
        assertThat(item.externalId()).isEqualTo("T000901#2");
        assertThat(item.refExternalId()).isEqualTo("T000148#3");
        assertThat(item.rankOrder()).isEqualTo(1);
        assertThat(item.reasonCode()).isEqualTo("SIMILAR_PREFERRED_MOMENT");
    }

    @Test
    @DisplayName("사유 문장은 우리가 만든다 — 저쪽이 보내지 않는다")
    void reasonIsBuiltHere() {
        // 화면에 나가는 문장이다. `dna3` 나 `0.812` 같은 내부 값이 섞이면 안 된다.
        for (String code : List.of("COLD_START", "SIMILAR_PREFERRED_MOMENT",
                                   "EXPLORE_NEW", "처음 보는 코드")) {
            String text = RecommendReason.of(code, SectionLabel.CHORUS);
            assertThat(text).isNotBlank();
            assertThat(text).doesNotContain("#").doesNotContain("dna");
            assertThat(text).doesNotMatch(".*\\d+\\.\\d+.*");
        }
        // 모르는 코드에서 빈 문자열이 나가면 화면이 빈다.
        assertThat(RecommendReason.of(null, SectionLabel.VERSE)).isNotBlank();
    }

    @Test
    @DisplayName("items 가 없어도 터지지 않는다")
    void missingItems() {
        String body = "{ \"recommendation_id\": 1, \"shortfall\": 3, \"model_version\": \"v\" }";

        RecommenderDto.Response response =
            AiJson.read(body.getBytes(StandardCharsets.UTF_8), RecommenderDto.Response.class);

        assertThat(response.safeItems()).isEmpty();
        assertThat(response.shortfall()).isEqualTo(3);
    }

    @Test
    @DisplayName("오류 본문에서 error_code 를 읽는다")
    void readError() {
        String body = "{ \"error_code\": \"NO_CANDIDATES\", \"message\": \"해당되는 노래가 없습니다.\" }";

        RecommenderDto.Error error =
            AiJson.read(body.getBytes(StandardCharsets.UTF_8), RecommenderDto.Error.class);

        // 상태 코드만으로는 못 가른다. 404 에는 매핑 없음(장애)과 후보 없음(정상)이 섞여 있다.
        assertThat(error.errorCode()).isEqualTo("NO_CANDIDATES");
    }

    @Test
    @DisplayName("태그 어휘는 모양이 달라도 받을 수 있다")
    void readFacets() {
        String body = """
            { "catalog": { "tracks": 111, "moments": 65540 },
              "fields": {
                "genre": { "enforcement": "gate", "excludable": true,
                           "values": [ { "value": "pop", "tracks": 42 } ],
                           "style_values": [ { "value": "ko_ballad", "tracks": 9 } ] },
                "bpm_min": { "enforcement": "gate", "range": [60, 180] } },
              "example": { "filters": { "genre": ["pop"] } } }
            """;

        RecommenderDto.Facets facets =
            AiJson.read(body.getBytes(StandardCharsets.UTF_8), RecommenderDto.Facets.class);

        assertThat(facets.catalog()).containsEntry("tracks", 111);
        assertThat(facets.fields()).containsKeys("genre", "bpm_min");
    }
}
