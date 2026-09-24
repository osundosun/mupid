package com.osundosun.mupid.api.ai.catalog;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommenderCatalogServiceImpl implements RecommenderCatalogService {

    /**
     * 캐시 수명. 분석 배치가 곡을 올린 뒤 이만큼 지나면 추천에 나온다. 짧게 잡을수록 표를
     * 자주 읽는다 — 1천 곡이면 한 번에 수십 ms 다.
     */
    static final Duration TTL = Duration.ofMinutes(1);

    private final RecommenderCatalogRepository repository;

    private volatile RecommenderCatalog cached = RecommenderCatalog.empty();

    @Override
    @Transactional(readOnly = true)
    public RecommenderCatalog current() {
        RecommenderCatalog snap = cached;
        if (Duration.between(snap.builtAt(), Instant.now()).compareTo(TTL) < 0) {
            return snap;
        }
        synchronized (this) {
            snap = cached;
            if (Duration.between(snap.builtAt(), Instant.now()).compareTo(TTL) < 0) {
                return snap;
            }
            RecommenderCatalog built = build();
            cached = built;
            return built;
        }
    }

    /**
     * 표마다 한 번씩 읽어 붙인다. 후보 조건은 {@code findCandidateMusic} 한 곳에만 있다 —
     * 나머지는 그 결과에 없는 곡을 버린다. 트랜잭션은 {@link #current()} 가 연다.
     */
    RecommenderCatalog build() {
        long t0 = System.nanoTime();
        Map<Integer, Integer> duration = new LinkedHashMap<>();
        for (RecommenderCatalogRepository.CandidateRow row : repository.findCandidateMusic()) {
            duration.put(row.musicId(), row.durationMs());
        }
        Map<Integer, RecommenderCatalogRepository.AnalysisRow> analysis = new LinkedHashMap<>();
        for (RecommenderCatalogRepository.AnalysisRow row : repository.findAnalysis()) {
            analysis.put(row.musicId(), row);
        }
        Map<Integer, List<String>> genres = group(repository.findGenres());
        Map<Integer, List<String>> moods = group(repository.findMoods());
        Map<Integer, List<String>> instruments = group(repository.findInstruments());

        // 곡의 문자열 ID 는 구간 external_id 의 `#` 앞이다. 숫자에서 지어내지 않는다.
        Map<Integer, String> trackOf = new LinkedHashMap<>();
        List<RecommenderCatalog.Section> sections = new ArrayList<>();
        int conflicted = 0;
        for (MusicSectionAnalysis s : repository.findSections()) {
            int musicId = s.getMusic().getMusicId();
            if (!duration.containsKey(musicId)) {
                continue;                                   // 재생할 수 없는 곡
            }
            String uid = s.getExternalId() == null ? "" : s.getExternalId().strip();
            int hash = uid.indexOf('#');
            if (hash <= 0) {
                continue;
            }
            String track = uid.substring(0, hash);
            String seen = trackOf.putIfAbsent(musicId, track);
            if (seen != null && !seen.equals(track)) {
                // 한 곡의 구간이 서로 다른 문자열 곡을 가리킨다. 섞어 보내면 어느 쪽이
                // 이겼는지 모른 채 추천이 돈다. 그 구간은 싣지 않는다.
                conflicted++;
                continue;
            }
            sections.add(new RecommenderCatalog.Section(
                uid, track, s.getSectionLabel().name(), s.getStartTimeMs(), s.getEndTimeMs(),
                s.getEnergy(), instruments.getOrDefault(s.getMusicSectionAnalysisId(), List.of())));
        }

        List<RecommenderCatalog.Track> tracks = new ArrayList<>();
        for (Map.Entry<Integer, String> e : trackOf.entrySet()) {
            int musicId = e.getKey();
            RecommenderCatalogRepository.AnalysisRow a = analysis.get(musicId);
            tracks.add(new RecommenderCatalog.Track(
                e.getValue(), musicId, duration.get(musicId),
                a == null ? null : a.bpm(),
                a == null ? null : a.bpmRequestedMin(),
                a == null ? null : a.bpmRequestedMax(),
                a == null ? null : a.language(),
                a == null ? null : a.category(),
                genres.getOrDefault(musicId, List.of()),
                moods.getOrDefault(musicId, List.of()),
                a == null ? null : a.hasVocal(),
                a == null || a.vocalGender() == null ? null : a.vocalGender().name()));
        }
        if (conflicted > 0) {
            log.warn("추천 후보: 한 곡의 구간이 두 문자열 곡을 가리켜 {}건을 뺐다", conflicted);
        }
        log.info("추천 후보 {}곡 {}구간 ({}ms)", tracks.size(), sections.size(),
            (System.nanoTime() - t0) / 1_000_000);
        return new RecommenderCatalog(List.copyOf(tracks), List.copyOf(sections), Instant.now());
    }

    private static Map<Integer, List<String>> group(List<RecommenderCatalogRepository.CodeRow> rows) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (RecommenderCatalogRepository.CodeRow row : rows) {
            out.computeIfAbsent(row.ownerId(), k -> new ArrayList<>()).add(row.codeName());
        }
        return out;
    }

}
