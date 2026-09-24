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
 * 조건 필터와 조건 어휘가 <b>함께 쓰는</b> 곡 파생값. 한 곳에서만 계산한다.
 *
 * <p>규칙은 예전 추천 서버({@code ai_server/app/recommender.py} 의 {@code _catalog_tags}·
 * {@code _vocal_values}·{@code parse_style}·{@code catalog_facets}·{@code _qvals}·
 * {@code _qmatch})와 같다. 조건을 거는 쪽이 Spring 으로 옮겨 왔을 뿐 판정은 바꾸지 않는다
 * (연동 계약 2026-09-23 절). 두 곳이 같은 규칙을 따로 들면 한쪽만 고쳐지므로 추천 서버에서는
 * 지웠다.
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
    /** 구간 → 검색에 쓰는 악기(구체적인 것만). */
    final Map<String, List<String>> sectionInstruments;

    CatalogFacts(RecommenderCatalog catalog) {
        Map<String, List<String>> union = new LinkedHashMap<>();
        sectionInstruments = new LinkedHashMap<>();
        for (RecommenderCatalog.Section s : catalog.sections()) {
            List<String> specific = specificInstruments(s.instruments());
            sectionInstruments.put(s.externalId(), specific);
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

    /**
     * 비교용 정규화. 소문자, {@code -}·공백은 {@code _}. 값 하나든 목록이든 받는다.
     */
    static List<String> qvals(Object v) {
        if (v == null) {
            return List.of();
        }
        Collection<?> items = v instanceof Collection<?> c ? c : List.of(v);
        List<String> out = new ArrayList<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            String s = String.valueOf(item).strip();
            if (s.isEmpty()) {
                continue;
            }
            out.add(s.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        }
        return out;
    }

    /**
     * {@code _} 경계 부분 일치 — {@code rock} 은 {@code alternative_rock} 에 맞는다.
     */
    static boolean qmatch(Object values, Object wanted) {
        List<String> have = qvals(values);
        for (String w : qvals(wanted)) {
            for (String h : have) {
                if (w.equals(h) || ("_" + h + "_").contains("_" + w + "_")) {
                    return true;
                }
            }
        }
        return false;
    }

}
