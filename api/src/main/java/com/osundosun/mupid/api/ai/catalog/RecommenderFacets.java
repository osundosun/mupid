package com.osundosun.mupid.api.ai.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.osundosun.mupid.api.ai.client.RecommenderDto;

/**
 * 조건에 쓸 수 있는 어휘. 대화 모델이 QueryPlan 을 만들 때 이 값만 고르게 한다.
 *
 * <p>예전에는 추천 서버의 {@code GET /internal/facets} 가 만들었다. 어휘의 정본이 MySQL 이라
 * 여기서 만든다(연동 계약 2026-09-23 절). 계약 v3(S15P21A204-458)에서 조건을 <b>거는</b> 쪽은 다시
 * 추천 서버로 갔지만 어휘는 그대로 여기다 — 추천 서버가 거는 값(ClickHouse 사본)이 같은 MySQL 에서
 * 오므로 "알려 준 값인데 걸리지 않는" 조건이 생기지 않는다. 사본이 늦는 동안의 틈은
 * {@code UNSATISFIABLE_CONDITIONS} 로 드러난다(결과 0, 조건을 풀지 않는다).
 *
 * <p>응답 모양은 예전과 같다({@link RecommenderDto.Facets}) — 쓰는 쪽({@code AiRecommendToolImpl})을
 * 바꾸지 않는다.
 */
public final class RecommenderFacets {

    /** 칸마다 어떻게 걸리는지. 이걸 알려 주지 않으면 모델이 {@code mood} 로 거를 수 있다고 믿는다. */
    static final Map<String, String> ENFORCEMENT;
    static final Set<String> EXCLUDABLE = Set.of("genre", "language", "vocal", "instrument", "mood", "flow");

    /** 너무 드문 무드·악기는 선택지에서 뺀다. */
    private static final int MIN_TRACKS = 2;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("genre", "gate");
        m.put("language", "gate");
        m.put("vocal", "gate");
        m.put("instrument", "gate");
        m.put("flow", "gate");
        m.put("mood", "score_only");               // exclude.mood 는 게이트다
        m.put("bpm_min", "gate");
        m.put("bpm_max", "gate");
        m.put("tempo_direction", "gate_with_reference");
        m.put("energy_direction", "gate_with_reference");
        m.put("activity", "ignored");
        m.put("goal", "ignored");
        ENFORCEMENT = java.util.Collections.unmodifiableMap(m);   // 칸 순서를 고정한다
    }

    private RecommenderFacets() {
    }

    public static RecommenderDto.Facets of(RecommenderCatalog catalog) {
        CatalogFacts facts = new CatalogFacts(catalog);
        Map<String, Integer> genre = new LinkedHashMap<>();
        Map<String, Integer> style = new LinkedHashMap<>();
        Map<String, Integer> language = new LinkedHashMap<>();
        Map<String, Integer> vocal = new LinkedHashMap<>();
        Map<String, Integer> mood = new LinkedHashMap<>();
        Map<String, Integer> instrument = new LinkedHashMap<>();
        double bpmLo = Double.MAX_VALUE;
        double bpmHi = 0.0;
        for (CatalogFacts.TrackFacet t : facts.byTrack.values()) {
            t.genres().forEach(g -> genre.merge(g, 1, Integer::sum));
            count(style, t.style());
            count(language, t.language());
            t.vocal().forEach(v -> vocal.merge(v, 1, Integer::sum));
            t.moods().forEach(v -> mood.merge(v, 1, Integer::sum));
            t.instruments().forEach(v -> instrument.merge(v, 1, Integer::sum));
            if (t.bpm() > 0) {
                bpmLo = Math.min(bpmLo, t.bpm());
                bpmHi = Math.max(bpmHi, t.bpm());
            }
        }
        Map<String, Integer> flow = new LinkedHashMap<>();
        for (RecommenderCatalog.Section s : catalog.sections()) {
            flow.merge(s.sectionLabel().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ENFORCEMENT.entrySet()) {
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("enforcement", e.getValue());
            spec.put("excludable", EXCLUDABLE.contains(e.getKey()));
            fields.put(e.getKey(), spec);
        }
        field(fields, "genre").put("values", values(genre, "tracks", 1));
        // 장르 게이트는 genre 와 style 양쪽에 맞춰 본다. 둘 다 골라도 된다.
        field(fields, "genre").put("style_values", values(style, "tracks", 1));
        field(fields, "language").put("values", values(language, "tracks", 1));
        field(fields, "vocal").put("values", values(vocal, "tracks", 1));
        field(fields, "instrument").put("values", values(instrument, "tracks", MIN_TRACKS));
        field(fields, "mood").put("values", values(mood, "tracks", MIN_TRACKS));
        field(fields, "flow").put("values", values(flow, "moments", 1));
        List<Long> range = bpmHi > 0 ? List.of(Math.round(bpmLo), Math.round(bpmHi)) : null;
        field(fields, "bpm_min").put("range", range);
        field(fields, "bpm_max").put("range", range);
        List<Map<String, Object>> directions = List.of(Map.of("value", "up"), Map.of("value", "down"));
        field(fields, "tempo_direction").put("values", directions);
        field(fields, "energy_direction").put("values", directions);

        return new RecommenderDto.Facets(
            Map.of("tracks", catalog.tracks().size(), "moments", catalog.sections().size()),
            fields,
            Map.of("filters", Map.of("genre", List.of("pop"), "instrument", List.of("piano"),
                    "vocal", List.of("female"), "flow", List.of("chorus")),
                "exclude", Map.of("mood", List.of("aggressive"))));
    }

    private static void count(Map<String, Integer> acc, String value) {
        if (value != null && !value.isBlank()) {
            acc.merge(value, 1, Integer::sum);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> field(Map<String, Object> fields, String name) {
        return (Map<String, Object>) fields.get(name);
    }

    /** 많은 순. 같으면 먼저 본 순서다(곡 순서가 고정이라 결과도 고정이다). */
    private static List<Map<String, Object>> values(Map<String, Integer> counts, String unit, int minimum) {
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) {
            if (e.getValue() >= minimum) {
                out.add(Map.of("value", e.getKey(), unit, e.getValue()));
            }
        }
        return out;
    }

}
