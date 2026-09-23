package com.osundosun.mupid.api.ai.catalog;

import java.time.Instant;
import java.util.List;

/**
 * 시험용 추천 후보. DB 없이 {@link RecommenderCatalogService} 를 채운다.
 *
 * <p>대화 추천 시험들이 거는 조건({@code genre: poprock·pop}, {@code instrument: piano})을
 * 만족하도록 짜 두었다 — 조건을 걸었는데 후보가 없으면 추천 서버를 부르지 않고 끝나서, 그
 * 시험들이 보려는 경로에 닿지 못한다.
 */
public final class TestCatalogs {

    private TestCatalogs() {
    }

    public static RecommenderCatalog sample() {
        return new RecommenderCatalog(
            List.of(
                track("T000901", List.of("poprock"), "female_korean_ballad", true, "F", 120.0),
                track("T000902", List.of("pop"), "male_english_pop", true, "M", 96.0)),
            List.of(
                section("T000901#2", "T000901", "CHORUS", 45_000, 60_000, List.of("piano", "드럼")),
                section("T000902#1", "T000902", "VERSE", 15_000, 40_000, List.of("piano"))),
            Instant.now());
    }

    /** 늘 같은 후보를 돌려주는 서비스. */
    public static RecommenderCatalogService fixed() {
        RecommenderCatalog catalog = sample();
        return () -> catalog;
    }

    public static RecommenderCatalog.Track track(String id, List<String> genres, String category,
                                                 Boolean hasVocal, String gender, Double bpm) {
        String language = category.contains("korean") ? "korean"
            : category.contains("english") ? "english" : null;
        return new RecommenderCatalog.Track(id, id.hashCode() & 0xffff, 180_000, bpm, null, null,
            language, category, genres, List.of("calm"), hasVocal, gender);
    }

    public static RecommenderCatalog.Section section(String uid, String track, String label,
                                                     int startMs, int endMs, List<String> instruments) {
        return new RecommenderCatalog.Section(uid, track, label, startMs, endMs, 0.08, instruments);
    }

}
