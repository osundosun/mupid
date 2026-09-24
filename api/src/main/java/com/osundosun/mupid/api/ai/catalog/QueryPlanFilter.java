package com.osundosun.mupid.api.ai.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 검색 조건(QueryPlan)을 <b>여기서</b> 건다. 통과한 구간만 추천 서버에 보낸다.
 *
 * <p>연동 계약 2026-09-23 절 — MySQL 이 정본인 판단은 Spring 이 끝낸다. 규칙은 예전 추천
 * 서버의 {@code filter_query_pool}·{@code _canonicalize_query_tags} 와 같다. 추천 서버에는
 * 무드 가점·CLAP 만 남았다.
 *
 * <h2>하는 일</h2>
 * <ol>
 *   <li><b>어휘 맞춤</b> — 요청한 값 가운데 이 카탈로그에 실제로 있는 것만 남긴다
 *   <li><b>만족할 수 없는 조건</b> — 장르·언어·보컬·악기 중 요청했는데 하나도 남지 않은 것이
 *       있으면 <b>빈 결과</b>다. 조건을 몰래 풀면 사용자가 말한 것과 다른 곡이 나간다
 *   <li><b>게이트</b> — 장르(곡 장르 ∪ 스타일)·언어·악기(구간, 없으면 곡)·보컬·구간형,
 *       빼는 조건(무드 포함), BPM 범위·템포/에너지 방향
 * </ol>
 *
 * <p>{@code filters.mood} 는 거르지 않는다 — 추론 폭이 커서 걸러내면 후보가 과하게 마른다.
 * 추천 서버가 가점으로 쓴다. {@code activity}·{@code goal} 은 카탈로그 필드가 없어 무시하고
 * 진단에 남긴다.
 */
public final class QueryPlanFilter {

    /** 필수 조건 가운데 "어휘에 없음" 을 따지는 칸. */
    private static final List<String> REQUIRED = List.of("genre", "language", "vocal", "instrument");

    /** 만족시킬 수 없는 조건 하나. */
    public record Unsatisfiable(String field, List<String> requested) {
    }

    /**
     * @param kept          통과한 구간. 비었으면 추천 서버를 부르지 않는다
     * @param unsatisfiable 어휘에 없어 통째로 사라진 필수 조건
     * @param applied       실제로 건 조건 ({@code filters.genre} 처럼)
     * @param ignored       판정할 수 없어 무시한 조건과 그 이유
     */
    public record Outcome(
        List<RecommenderCatalog.Section> kept,
        List<Unsatisfiable> unsatisfiable,
        List<String> applied,
        List<String> ignored
    ) {
        public boolean satisfiable() {
            return unsatisfiable.isEmpty();
        }

        public Set<String> keptIds() {
            Set<String> out = new LinkedHashSet<>();
            kept.forEach(s -> out.add(s.externalId()));
            return out;
        }
    }

    private QueryPlanFilter() {
    }

    /**
     * @param plan           {@code {"filters": {...}, "exclude": {...}}}
     * @param currentTrack   템포 방향의 기준 곡. 없으면 그 조건은 무시된다
     * @param currentSection 에너지 방향의 기준 구간. 없으면 기준 곡의 구간 평균이다
     */
    public static Outcome apply(RecommenderCatalog catalog, Map<String, Object> plan,
                                String currentTrack, String currentSection) {
        CatalogFacts facts = new CatalogFacts(catalog);
        Map<String, Object> rawF = section(plan, "filters");
        Map<String, Object> rawEx = section(plan, "exclude");

        Map<String, Set<String>> vocab = vocabulary(catalog, facts);
        Map<String, List<String>> f = canonical(rawF, vocab);
        Map<String, List<String>> ex = canonical(rawEx, vocab);

        List<String> ignored = new ArrayList<>();
        Map<String, Boolean> available = Map.of(
            "instrument", facts.byTrack.values().stream().anyMatch(t -> !t.instruments().isEmpty()),
            "vocal", facts.byTrack.values().stream().anyMatch(t -> !t.vocal().isEmpty()));
        for (Map.Entry<String, Boolean> a : available.entrySet()) {
            String name = a.getKey();
            if (!a.getValue() && (!CatalogFacts.qvals(rawF.get(name)).isEmpty()
                || !CatalogFacts.qvals(rawEx.get(name)).isEmpty())) {
                ignored.add(name + "(카탈로그 데이터 없음)");
                f.put(name, List.of());
                ex.put(name, List.of());
            }
        }

        // 사전에 없어 통째로 사라진 필수 조건. 조용히 넘기면 조건을 푼 것이 된다.
        List<Unsatisfiable> unsat = new ArrayList<>();
        for (String name : REQUIRED) {
            List<String> asked = CatalogFacts.qvals(rawF.get(name));
            if (!asked.isEmpty() && available.getOrDefault(name, true) && f.get(name).isEmpty()) {
                unsat.add(new Unsatisfiable(name, asked));
            }
        }
        if (!unsat.isEmpty()) {
            return new Outcome(List.of(), List.copyOf(unsat), List.of(), List.copyOf(ignored));
        }

        List<RecommenderCatalog.Section> pool = catalog.sections();
        boolean[] keep = new boolean[pool.size()];
        java.util.Arrays.fill(keep, true);
        List<String> applied = new ArrayList<>();

        Function<RecommenderCatalog.Section, CatalogFacts.TrackFacet> trackOf =
            s -> facts.byTrack.get(s.track());
        Function<RecommenderCatalog.Section, List<String>> genreValues = s -> {
            CatalogFacts.TrackFacet t = trackOf.apply(s);
            if (t == null) {
                return List.of();
            }
            List<String> v = new ArrayList<>(t.genres());
            v.add(t.style());
            return v;
        };
        Function<RecommenderCatalog.Section, List<String>> languageValues = s -> {
            CatalogFacts.TrackFacet t = trackOf.apply(s);
            return t == null ? List.of() : List.of(t.language());
        };
        Function<RecommenderCatalog.Section, List<String>> instrumentValues = s -> {
            List<String> own = facts.sectionInstruments.getOrDefault(s.externalId(), List.of());
            if (!own.isEmpty()) {
                return own;
            }
            CatalogFacts.TrackFacet t = trackOf.apply(s);
            return t == null ? List.of() : t.instruments();       // 구간에 없으면 곡의 합집합
        };
        Function<RecommenderCatalog.Section, List<String>> vocalValues = s -> {
            CatalogFacts.TrackFacet t = trackOf.apply(s);
            return t == null ? List.of() : t.vocal();
        };
        Function<RecommenderCatalog.Section, List<String>> moodValues = s -> {
            CatalogFacts.TrackFacet t = trackOf.apply(s);
            return t == null ? List.of() : t.moods();
        };

        gate(pool, keep, applied, "filters.genre", f.get("genre"), genreValues, false);
        gate(pool, keep, applied, "filters.language", f.get("language"), languageValues, false);
        gate(pool, keep, applied, "filters.instrument", f.get("instrument"), instrumentValues, false);
        gate(pool, keep, applied, "filters.vocal", f.get("vocal"), vocalValues, false);
        gate(pool, keep, applied, "exclude.genre", ex.get("genre"), genreValues, true);
        gate(pool, keep, applied, "exclude.language", ex.get("language"), languageValues, true);
        gate(pool, keep, applied, "exclude.mood", ex.get("mood"), moodValues, true);
        gate(pool, keep, applied, "exclude.instrument", ex.get("instrument"), instrumentValues, true);
        gate(pool, keep, applied, "exclude.vocal", ex.get("vocal"), vocalValues, true);

        Function<RecommenderCatalog.Section, List<String>> flowValues =
            s -> List.of(s.sectionLabel().toLowerCase(Locale.ROOT));
        List<String> flow = f.get("flow");
        if (!flow.isEmpty()) {
            // 구간형은 하나만 고른다 — 예전 계약과 같다.
            gate(pool, keep, applied, "filters.flow", List.of(flow.get(0)), flowValues, false);
        }
        gate(pool, keep, applied, "exclude.flow", ex.get("flow"), flowValues, true);

        Double bpmMin = number(rawF.get("bpm_min"));
        Double bpmMax = number(rawF.get("bpm_max"));
        if (bpmMin != null) {
            for (int i = 0; i < pool.size(); i++) {
                keep[i] &= bpmOf(facts, pool.get(i)) >= bpmMin;
            }
            applied.add("filters.bpm_min");
        }
        if (bpmMax != null) {
            for (int i = 0; i < pool.size(); i++) {
                keep[i] &= bpmOf(facts, pool.get(i)) <= bpmMax;
            }
            applied.add("filters.bpm_max");
        }

        String tempo = lower(rawF.get("tempo_direction"));
        CatalogFacts.TrackFacet ref = currentTrack == null ? null : facts.byTrack.get(currentTrack);
        double refBpm = ref == null ? 0.0 : ref.bpm();
        if (Set.of("up", "faster", "fast").contains(tempo) && refBpm > 0) {
            for (int i = 0; i < pool.size(); i++) {
                keep[i] &= bpmOf(facts, pool.get(i)) > refBpm;
            }
            applied.add("filters.tempo_direction");
        } else if (Set.of("down", "slower", "slow").contains(tempo) && refBpm > 0) {
            for (int i = 0; i < pool.size(); i++) {
                double b = bpmOf(facts, pool.get(i));
                keep[i] &= b > 0 && b < refBpm;
            }
            applied.add("filters.tempo_direction");
        } else if (!tempo.isEmpty()) {
            ignored.add("filters.tempo_direction(기준곡 없음)");
        }

        String energy = lower(rawF.get("energy_direction"));
        double refEnergy = referenceEnergy(pool, currentTrack, currentSection);
        if (Set.of("up", "higher", "energetic").contains(energy) && refEnergy > 0) {
            for (int i = 0; i < pool.size(); i++) {
                Double e = pool.get(i).energy();
                keep[i] &= e != null && e > refEnergy;
            }
            applied.add("filters.energy_direction");
        } else if (Set.of("down", "lower", "calm").contains(energy) && refEnergy > 0) {
            for (int i = 0; i < pool.size(); i++) {
                Double e = pool.get(i).energy();
                keep[i] &= e != null && e < refEnergy;
            }
            applied.add("filters.energy_direction");
        } else if (!energy.isEmpty()) {
            ignored.add("filters.energy_direction(기준곡 없음)");
        }

        for (String name : List.of("activity", "goal")) {
            if (!CatalogFacts.qvals(rawF.get(name)).isEmpty()
                || !CatalogFacts.qvals(rawEx.get(name)).isEmpty()) {
                ignored.add(name + "(카탈로그 필드 없음)");
            }
        }

        List<RecommenderCatalog.Section> kept = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            if (keep[i]) {
                kept.add(pool.get(i));
            }
        }
        return new Outcome(List.copyOf(kept), List.of(), List.copyOf(applied), List.copyOf(ignored));
    }

    /** 이 카탈로그에 실제로 있는 값. 장르 칸은 장르 코드와 스타일을 함께 받는다. */
    static Map<String, Set<String>> vocabulary(RecommenderCatalog catalog, CatalogFacts facts) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        Set<String> genre = new LinkedHashSet<>();
        Set<String> language = new LinkedHashSet<>();
        Set<String> mood = new LinkedHashSet<>();
        Set<String> instrument = new LinkedHashSet<>();
        Set<String> vocal = new LinkedHashSet<>();
        for (CatalogFacts.TrackFacet t : facts.byTrack.values()) {
            genre.addAll(CatalogFacts.qvals(t.genres()));
            genre.addAll(CatalogFacts.qvals(t.style()));
            language.addAll(CatalogFacts.qvals(t.language()));
            mood.addAll(CatalogFacts.qvals(t.moods()));
            instrument.addAll(CatalogFacts.qvals(t.instruments()));
            vocal.addAll(t.vocal());
        }
        Set<String> flow = new LinkedHashSet<>();
        for (RecommenderCatalog.Section s : catalog.sections()) {
            flow.addAll(CatalogFacts.qvals(s.sectionLabel()));
        }
        out.put("genre", genre);
        out.put("language", language);
        out.put("mood", mood);
        out.put("instrument", instrument);
        out.put("vocal", vocal);
        out.put("flow", flow);
        return out;
    }

    /**
     * 어휘에 있는 값만 남긴다. <b>어휘에 있는 값은 건드리지 않는다.</b>
     *
     * <p>예전 추천 서버의 별칭표({@code quiet → relaxing} 등)는 옮기지 않았다 — 오른쪽 값이
     * 어휘 전환(2026-09-21) 전의 태그라 지금 카탈로그에 하나도 없어서, 거쳐도 조건을
     * 만들지 못하고 떨어졌다. 동작이 같다.
     */
    private static Map<String, List<String>> canonical(Map<String, Object> raw,
                                                       Map<String, Set<String>> vocab) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : vocab.entrySet()) {
            List<String> kept = new ArrayList<>();
            for (String v : CatalogFacts.qvals(raw.get(e.getKey()))) {
                if (e.getValue().contains(v) && !kept.contains(v)) {
                    kept.add(v);
                }
            }
            out.put(e.getKey(), kept);
        }
        return out;
    }

    private static void gate(List<RecommenderCatalog.Section> pool, boolean[] keep, List<String> applied,
                             String name, List<String> wanted,
                             Function<RecommenderCatalog.Section, List<String>> values, boolean negate) {
        if (wanted == null || wanted.isEmpty()) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            boolean hit = CatalogFacts.qmatch(values.apply(pool.get(i)), wanted);
            keep[i] &= negate ? !hit : hit;
        }
        applied.add(name);
    }

    private static double bpmOf(CatalogFacts facts, RecommenderCatalog.Section s) {
        CatalogFacts.TrackFacet t = facts.byTrack.get(s.track());
        return t == null ? 0.0 : t.bpm();
    }

    /** 기준 구간의 에너지. 없으면 기준 곡 구간들의 평균, 그것도 없으면 0. */
    private static double referenceEnergy(List<RecommenderCatalog.Section> pool,
                                          String currentTrack, String currentSection) {
        if (currentSection != null) {
            for (RecommenderCatalog.Section s : pool) {
                if (s.externalId().equals(currentSection) && s.energy() != null) {
                    return s.energy();
                }
            }
        }
        if (currentTrack == null) {
            return 0.0;
        }
        return pool.stream().filter(s -> s.track().equals(currentTrack) && s.energy() != null)
            .mapToDouble(RecommenderCatalog.Section::energy).average().orElse(0.0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> plan, String key) {
        Object v = plan == null ? null : plan.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String lower(Object v) {
        return v == null ? "" : String.valueOf(v).strip().toLowerCase(Locale.ROOT);
    }

}
