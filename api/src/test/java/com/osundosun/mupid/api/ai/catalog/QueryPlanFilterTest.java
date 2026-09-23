package com.osundosun.mupid.api.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 검색 조건은 Spring 이 건다(연동 계약 2026-09-23 절). 규칙은 예전 추천 서버의
 * {@code filter_query_pool} 과 같다 — 그쪽 시험(`ai_server/tests/test_pipeline_synthetic.py`
 * 의 게이트 시험 6건)을 여기로 옮겼다.
 */
class QueryPlanFilterTest {

    private static final List<String> GENRES = List.of("contemporary_pop", "pop_ballad", "indie_rock");

    /** 곡 6개 × 구간 4개. 짝수 곡은 여성·피아노, 홀수 곡은 남성·기타. */
    private static RecommenderCatalog catalog() {
        List<RecommenderCatalog.Track> tracks = new java.util.ArrayList<>();
        List<RecommenderCatalog.Section> sections = new java.util.ArrayList<>();
        String[] labels = {"INTRO", "VERSE", "CHORUS", "BRIDGE"};
        for (int t = 0; t < 6; t++) {
            String id = "T%06d".formatted(t);
            tracks.add(new RecommenderCatalog.Track(id, 1000 + t, 120_000, 70.0 + t * 15,
                80, 120, t % 2 == 0 ? "ko" : "en", GENRES.get(t % 3),
                List.of(GENRES.get(t % 3)), List.of(t % 2 == 0 ? "calm" : "bright"),
                true, t % 2 == 0 ? "F" : "M"));
            for (int s = 0; s < 4; s++) {
                sections.add(new RecommenderCatalog.Section(id + "#" + s, id, labels[s],
                    s * 30_000, s * 30_000 + 28_000, 0.05 + 0.01 * s,
                    List.of(t % 2 == 0 ? "piano" : "guitar")));
            }
        }
        return new RecommenderCatalog(tracks, sections, Instant.now());
    }

    private static QueryPlanFilter.Outcome filter(Map<String, Object> plan) {
        return QueryPlanFilter.apply(catalog(), plan, null, null);
    }

    private static Map<String, Object> filters(String name, Object value) {
        return Map.of("filters", Map.of(name, value));
    }

    @Test
    @DisplayName("장르·보컬·악기 조건이 실제로 후보를 거른다 — 한 축이 안 실리면 아무것도 안 걸러진다")
    void everyAxisActuallyGates() {
        int full = catalog().sections().size();
        for (var axis : List.of(Map.entry("genre", GENRES.get(0)), Map.entry("vocal", "female"),
                                Map.entry("instrument", "piano"))) {
            QueryPlanFilter.Outcome out = filter(filters(axis.getKey(), List.of(axis.getValue())));
            assertThat(out.applied()).contains("filters." + axis.getKey());
            assertThat(out.kept()).hasSizeBetween(1, full - 1);
        }
        // 무드는 거르지 않는다(가점) — 빼는 쪽으로만 게이트다.
        QueryPlanFilter.Outcome moodOut = filter(Map.of("exclude", Map.of("mood", List.of("calm"))));
        assertThat(moodOut.applied()).containsExactly("exclude.mood");
        assertThat(moodOut.kept()).hasSizeBetween(1, full - 1);
        assertThat(filter(filters("mood", List.of("calm"))).kept()).hasSize(full);
    }

    @Test
    @DisplayName("어휘에 없는 필수 조건은 조용히 풀지 않는다 — 빈 결과와 그 이유")
    void unsatisfiableConditionReturnsNothing() {
        QueryPlanFilter.Outcome out = filter(filters("genre", List.of("존재하지않는장르")));

        assertThat(out.satisfiable()).isFalse();
        assertThat(out.kept()).isEmpty();
        assertThat(out.unsatisfiable()).extracting(QueryPlanFilter.Unsatisfiable::field)
            .containsExactly("genre");
    }

    @Test
    @DisplayName("카탈로그에 보컬·악기 데이터가 아예 없으면 그 조건은 무시한다 — 후보를 비우지 않는다")
    void missingDataIsIgnoredNotEmptying() {
        RecommenderCatalog bare = new RecommenderCatalog(
            catalog().tracks().stream().map(t -> new RecommenderCatalog.Track(
                t.track(), t.musicId(), t.durationMs(), t.bpm(), t.bpmRequestedMin(),
                t.bpmRequestedMax(), t.language(), t.category(), t.genres(), t.moods(), null, null))
                .toList(),
            catalog().sections().stream().map(s -> new RecommenderCatalog.Section(
                s.externalId(), s.track(), s.sectionLabel(), s.startMs(), s.endMs(), s.energy(),
                List.of())).toList(),
            Instant.now());

        QueryPlanFilter.Outcome out = QueryPlanFilter.apply(bare, Map.of("filters", Map.of(
            "vocal", List.of("female"), "instrument", List.of("piano"))), null, null);

        assertThat(out.satisfiable()).isTrue();
        assertThat(out.kept()).hasSize(bare.sections().size());
        assertThat(out.ignored()).contains("vocal(카탈로그 데이터 없음)", "instrument(카탈로그 데이터 없음)");
    }

    @Test
    @DisplayName("악기는 구간마다 본다 — 구간에 없으면 곡의 구간 합집합으로 본다")
    void instrumentUsesEachSectionThenTheTrackUnion() {
        RecommenderCatalog base = catalog();
        List<RecommenderCatalog.Section> sections = new java.util.ArrayList<>(base.sections());
        // T000000 의 #0 에만 드럼을 달고, #1 은 악기를 비운다(곡 합집합으로 떨어진다).
        sections.set(0, new RecommenderCatalog.Section("T000000#0", "T000000", "INTRO", 0, 28_000,
            0.05, List.of("drums")));
        sections.set(1, new RecommenderCatalog.Section("T000000#1", "T000000", "VERSE", 30_000, 58_000,
            0.06, List.of()));
        RecommenderCatalog cat = new RecommenderCatalog(base.tracks(), sections, Instant.now());

        QueryPlanFilter.Outcome drums = QueryPlanFilter.apply(cat, filters("instrument", List.of("drums")),
            null, null);

        assertThat(drums.keptIds()).containsExactlyInAnyOrder("T000000#0", "T000000#1");
    }

    @Test
    @DisplayName("'그 외 악기'·'other' 는 검색 근거가 아니다")
    void nonspecificInstrumentNeverMatches() {
        RecommenderCatalog base = catalog();
        List<RecommenderCatalog.Section> sections = base.sections().stream()
            .map(s -> new RecommenderCatalog.Section(s.externalId(), s.track(), s.sectionLabel(),
                s.startMs(), s.endMs(), s.energy(), List.of("그 외 악기", "other")))
            .toList();
        RecommenderCatalog cat = new RecommenderCatalog(base.tracks(), sections, Instant.now());

        QueryPlanFilter.Outcome out = QueryPlanFilter.apply(cat, filters("instrument", List.of("other")),
            null, null);

        // 구체적인 악기가 하나도 없으니 악기 조건 자체가 무시된다.
        assertThat(out.ignored()).contains("instrument(카탈로그 데이터 없음)");
    }

    @Test
    @DisplayName("어휘에 있는 값은 별칭으로 바꾸지 않는다 — 'calm' 은 그대로 걸린다")
    void aValueInTheVocabularyIsKept() {
        QueryPlanFilter.Outcome out = filter(Map.of("exclude", Map.of("mood", List.of("calm"))));

        assertThat(out.applied()).contains("exclude.mood");
        assertThat(out.kept()).allMatch(s -> Integer.parseInt(s.track().substring(1)) % 2 == 1);
    }

    @Test
    @DisplayName("같은 조건을 두 번 걸면 같은 결과다")
    void sameConditionSameResult() {
        Map<String, Object> plan = Map.of(
            "filters", Map.of("genre", List.of("contemporary_pop"), "language", List.of("ko")),
            "exclude", Map.of("mood", List.of("bright")));
        QueryPlanFilter.Outcome first = filter(plan);
        assertThat(first.kept()).isNotEmpty();
        assertThat(filter(plan)).isEqualTo(first);
    }

    @Test
    @DisplayName("요청 값은 어휘와 **정확히** 맞아야 남는다 — rock 이 indie_rock 으로 넓혀지지 않는다")
    void requestedValuesMustBeExactVocabulary() {
        // 예전 추천 서버(`_canonicalize_query_tags` 의 `value in vocab`)와 같다. 넓히면
        // "록" 을 물었는데 인디 록만 나오는 이유를 설명할 수 없다.
        QueryPlanFilter.Outcome rock = filter(filters("genre", List.of("rock")));
        assertThat(rock.satisfiable()).isFalse();

        assertThat(filter(filters("genre", List.of("indie_rock"))).kept())
            .isNotEmpty()
            .allMatch(s -> Integer.parseInt(s.track().substring(1)) % 3 == 2);
    }

    @Test
    @DisplayName("장르 조건은 스타일에도 맞춰 본다 — '_' 경계 부분 일치다")
    void genreMatchesTheStyleToo() {
        RecommenderCatalog styled = new RecommenderCatalog(
            List.of(TestCatalogs.track("S1", List.of("pop"), "female_korean_ballad", true, "F", 90.0)),
            List.of(TestCatalogs.section("S1#0", "S1", "CHORUS", 0, 20_000, List.of())),
            Instant.now());
        // category 에서 보컬·언어 접두어를 뗀 스타일(ballad)로도 장르 조건이 걸린다.
        assertThat(QueryPlanFilter.apply(styled, filters("genre", List.of("ballad")), null, null)
            .kept()).hasSize(1);
    }

    @Test
    @DisplayName("구간형(flow)은 구간 라벨을 소문자로 본다")
    void flowMatchesTheSectionLabel() {
        assertThat(filter(filters("flow", "chorus")).kept())
            .isNotEmpty().allMatch(s -> s.sectionLabel().equals("CHORUS"));
        assertThat(filter(Map.of("exclude", Map.of("flow", List.of("chorus")))).kept())
            .noneMatch(s -> s.sectionLabel().equals("CHORUS"));
    }

    @Test
    @DisplayName("BPM 범위는 요청 BPM 의 중간값으로 본다 — 측정값은 옥타브 오류가 잦다")
    void bpmRangeUsesTheRequestedMidpoint() {
        // 모든 곡의 요청 범위가 80~120 이라 중간값 100 이다(측정값은 70~145).
        assertThat(filter(filters("bpm_min", 100)).kept()).hasSize(24);
        assertThat(filter(filters("bpm_min", 101)).kept()).isEmpty();
    }

    @Test
    @DisplayName("템포·에너지 방향은 기준이 있어야 건다 — 없으면 무시하고 진단에 남긴다")
    void directionsNeedAReference() {
        QueryPlanFilter.Outcome noRef = filter(filters("tempo_direction", "up"));
        assertThat(noRef.ignored()).contains("filters.tempo_direction(기준곡 없음)");
        assertThat(noRef.kept()).hasSize(24);

        RecommenderCatalog cat = catalog();
        QueryPlanFilter.Outcome higher = QueryPlanFilter.apply(cat,
            filters("energy_direction", "up"), "T000000", "T000000#1");
        // 기준 구간 에너지 0.06 보다 높은 구간(#2·#3)만 남는다.
        assertThat(higher.kept()).isNotEmpty().allMatch(s -> s.energy() > 0.06);
    }

}
