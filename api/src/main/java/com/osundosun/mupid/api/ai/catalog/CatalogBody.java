package com.osundosun.mupid.api.ai.catalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 추천 요청 본문의 {@code catalog}. <b>열 단위다</b> — 같은 위치가 같은 행이다.
 *
 * <p>계약은 {@code infra/AI-SPRING-CONTRACT.md} 2026-09-23 절과 받는 쪽
 * ({@code ai_server/app/catalog_body.py})이 정한다. 이름은 {@code AiJson} 이 snake_case 로
 * 바꾼다({@code durationMs} → {@code duration_ms}).
 *
 * <p>행 객체 배열보다 약 60% 작다. 실측(1,034곡·9,098구간, 조건 없는 최악): 약 317KB,
 * gzip 62KB, 받는 쪽 파싱 4ms.
 *
 * <p><b>배열 안의 null 은 "모른다" 다.</b> 빠지면 뒤의 행이 한 칸씩 밀려 다른 곡의 값이 된다
 * — 그래서 {@code AiJson} 의 NON_NULL 이 배열 원소에 걸리지 않는지 시험으로 묶었다.
 */
public record CatalogBody(Tracks tracks, Sections sections) {

    public record Tracks(
        List<String> track,
        List<Integer> durationMs,
        List<Double> bpm,
        List<Integer> bpmRequestedMin,
        List<Integer> bpmRequestedMax,
        List<String> language,
        List<String> category,
        List<List<String>> genres,
        List<List<String>> moods,
        List<Boolean> hasVocal,
        List<String> vocalGender
    ) {
    }

    public record Sections(
        List<String> externalId,
        List<String> sectionLabel,
        List<Integer> startMs,
        List<Integer> endMs,
        List<Double> energy
    ) {
    }

    static CatalogBody of(List<RecommenderCatalog.Track> tracks,
                          List<RecommenderCatalog.Section> sections) {
        int n = tracks.size();
        List<String> track = new ArrayList<>(n);
        List<Integer> durationMs = new ArrayList<>(n);
        List<Double> bpm = new ArrayList<>(n);
        List<Integer> reqMin = new ArrayList<>(n);
        List<Integer> reqMax = new ArrayList<>(n);
        List<String> language = new ArrayList<>(n);
        List<String> category = new ArrayList<>(n);
        List<List<String>> genres = new ArrayList<>(n);
        List<List<String>> moods = new ArrayList<>(n);
        List<Boolean> hasVocal = new ArrayList<>(n);
        List<String> vocalGender = new ArrayList<>(n);
        for (RecommenderCatalog.Track t : tracks) {
            track.add(t.track());
            durationMs.add(t.durationMs());
            bpm.add(t.bpm());
            reqMin.add(t.bpmRequestedMin());
            reqMax.add(t.bpmRequestedMax());
            language.add(t.language());
            category.add(t.category());
            genres.add(t.genres());
            moods.add(t.moods());
            hasVocal.add(t.hasVocal());
            vocalGender.add(t.vocalGender());
        }
        int m = sections.size();
        List<String> externalId = new ArrayList<>(m);
        List<String> sectionLabel = new ArrayList<>(m);
        List<Integer> startMs = new ArrayList<>(m);
        List<Integer> endMs = new ArrayList<>(m);
        List<Double> energy = new ArrayList<>(m);
        for (RecommenderCatalog.Section s : sections) {
            externalId.add(s.externalId());
            sectionLabel.add(s.sectionLabel());
            startMs.add(s.startMs());
            endMs.add(s.endMs());
            energy.add(s.energy());
        }
        return new CatalogBody(
            new Tracks(track, durationMs, bpm, reqMin, reqMax, language, category, genres, moods,
                hasVocal, vocalGender),
            new Sections(externalId, sectionLabel, startMs, endMs, energy));
    }

    /** 실린 구간 수. 0 이면 추천 서버를 부를 이유가 없다. */
    public int sectionCount() {
        return sections.externalId().size();
    }

}
