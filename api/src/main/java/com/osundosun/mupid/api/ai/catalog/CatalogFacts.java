package com.osundosun.mupid.api.ai.catalog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 조건 어휘({@link RecommenderFacets})가 쓰는 곡 파생값.
 *
 * <p>규칙은 추천 서버가 조건을 걸 때 쓰는 것({@code _catalog_tags}·{@code _vocal_values}·
 * {@code parse_style})과 같다. 어휘가 알려 준 값이 추천 서버에서 걸리지 않으면 "알려 준 값인데 결과
 * 0" 이 된다. 조건을 거는 판정({@code _qvals}·{@code _qmatch}, 예전 {@code QueryPlanFilter})은 계약 v3
 * (S15P21A204-458)에서 추천 서버로 되돌아갔다 — 여기에는 어휘를 세는 데 필요한 것만 남는다.
 */
final class CatalogFacts {

    /**
     * 검색 근거로 쓰지 않는 악기 이름 — demucs 스템 넷 중 {@code other}.
     * <b>code_name 과 display_name 을 둘 다 적는다</b>(코드표가 한국어→영문으로 바뀌는 중).
     */
    static final Set<String> NONSPECIFIC_INSTRUMENTS = Set.of("other", "그 외 악기", "그외 악기");

    private static final Map<String, String> GENDER_WORD = Map.of(
        "M", "male", "F", "female", "MIX", "mixed");

    /** 곡 하나의 파생값. */
    record TrackFacet(
        RecommenderCatalog.Track track,
        List<String> genres,
        String style,
        String language,
        List<String> vocal,
        List<String> moods,
        List<String> instruments,
        double bpm
    ) {
    }

    final Map<String, TrackFacet> byTrack;

    CatalogFacts(RecommenderCatalog catalog) {
        Map<String, List<String>> union = new LinkedHashMap<>();
        for (RecommenderCatalog.Section s : catalog.sections()) {
            List<String> specific = specificInstruments(s.instruments());
            List<String> bucket = union.computeIfAbsent(s.track(), k -> new ArrayList<>());
            for (String name : specific) {
                if (!bucket.contains(name)) {
                    bucket.add(name);
                }
            }
        }
        byTrack = new LinkedHashMap<>();
        for (RecommenderCatalog.Track t : catalog.tracks()) {
            List<String> vocal = vocalValues(t.hasVocal(), t.vocalGender());
            String language = text(t.language());
            byTrack.put(t.track(), new TrackFacet(
                t,
                t.genres().stream().map(CatalogFacts::text).filter(v -> !v.isEmpty()).toList(),
                parseStyle(text(t.category()), vocal, language),
                language,
                vocal,
                t.moods().stream().map(CatalogFacts::text).filter(v -> !v.isEmpty()).toList(),
                union.getOrDefault(t.track(), List.of()),
                facetBpm(t)));
        }
    }

    /** 실제 두 컬럼을 검색 어휘로 투영한다. 모르면 빈 목록이다. */
    static List<String> vocalValues(Boolean hasVocal, String vocalGender) {
        if (hasVocal == null) {
            return List.of();
        }
        if (!hasVocal) {
            return List.of("instrumental");
        }
        String gender = vocalGender == null ? null : GENDER_WORD.get(vocalGender);
        return gender == null ? List.of("voice") : List.of("voice", gender);
    }

    /** category 에서 보컬·언어 접두어를 떼어 스타일만 남긴다({@code female_korean_ballad} → {@code ballad}). */
    static String parseStyle(String category, List<String> vocal, String language) {
        List<String> parts = new ArrayList<>(Arrays.stream(category.split("_"))
            .filter(p -> !p.isEmpty()).toList());
        if (!parts.isEmpty() && vocal.contains(parts.get(0))) {
            parts.remove(0);
        }
        if (!parts.isEmpty() && parts.get(0).equals(language)) {
            parts.remove(0);
        }
        return String.join("_", parts);
    }

    /**
     * 조건에 쓰는 BPM. 측정 BPM 은 옥타브 오류가 잦아서, 생성 때 요청한 범위가 있으면
     * 그 중간값을 쓴다. 둘 다 없으면 0 이다.
     */
    static double facetBpm(RecommenderCatalog.Track t) {
        Integer lo = t.bpmRequestedMin();
        Integer hi = t.bpmRequestedMax();
        if (lo != null && hi != null && lo > 0) {
            return (lo + hi) / 2.0;
        }
        return t.bpm() == null ? 0.0 : t.bpm();
    }

    static List<String> specificInstruments(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            String v = text(name);
            if (!v.isEmpty() && !NONSPECIFIC_INSTRUMENTS.contains(v.toLowerCase(Locale.ROOT))) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    /** 누락 표기를 어휘로 노출하지 않는다. */
    static String text(String v) {
        if (v == null) {
            return "";
        }
        String s = v.strip();
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "", "unknown", "nan", "none", "null", "<na>", "nat" -> "";
            default -> s;
        };
    }

}
