package com.osundosun.mupid.api.ai.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 추천 후보 한 벌. <b>만들고 나면 바꾸지 않는다.</b>
 *
 * <p>추천 서버는 Spring 을 부르지 않는다(연동 계약 2026-09-23 절, S15P21A204-423). 곡·구간
 * 메타의 정본은 MySQL 이고, 추천을 부를 때마다 이 객체가 만든 {@link CatalogBody} 를 요청
 * 본문에 싣는다. 예전에는 추천 서버가 ROLE_SYSTEM 토큰으로 내부 카탈로그 API 를 당겨 갔고,
 * 그 토큰이 만료되자 추천 서버가 뜨지 못했다.
 *
 * <p>여기 드는 것은 <b>추천할 수 있는 것뿐이다</b> — 지워지지 않았고, 재생할 스트림이 있고,
 * 구간이 있는 곡. Jamendo 같은 학습용 곡은 MySQL 에 없으므로 들어올 길이 없다.
 *
 * @param tracks   곡. 문자열 곡 ID 순서다
 * @param sections 구간. 곡 순서 안에서 시작 시각 순서다
 * @param builtAt  만든 시각. 캐시가 낡았는지 판단한다
 */
public record RecommenderCatalog(List<Track> tracks, List<Section> sections, Instant builtAt) {

    /**
     * 곡 한 줄.
     *
     * @param track            문자열 곡 ID. {@code external_id} 의 {@code #} 앞이다
     * @param bpm              측정 BPM. 모르면 null
     * @param bpmRequestedMin  생성 때 요청한 BPM 범위. 모르면 null
     * @param genres           장르 코드 이름
     * @param moods            곡 무드 코드 이름
     * @param hasVocal         보컬 유무. 모르면 null
     * @param vocalGender      {@code M}·{@code F}·{@code MIX}. 모르면 null
     */
    public record Track(
        String track,
        int musicId,
        Integer durationMs,
        Double bpm,
        Integer bpmRequestedMin,
        Integer bpmRequestedMax,
        String language,
        String category,
        List<String> genres,
        List<String> moods,
        Boolean hasVocal,
        String vocalGender
    ) {
    }

    /**
     * 구간 한 줄.
     *
     * @param sectionLabel MySQL ENUM 이름 그대로({@code CHORUS})
     * @param energy       구간 RMS(0~1). 모르면 null
     * @param instruments  이 구간의 악기 코드 이름. <b>본문에는 싣지 않는다</b> — 악기 조건은
     *                     여기서 걸고, 추천 서버의 계산에는 쓰이지 않는다
     */
    public record Section(
        String externalId,
        String track,
        String sectionLabel,
        int startMs,
        int endMs,
        Double energy,
        List<String> instruments
    ) {
    }

    public static RecommenderCatalog empty() {
        return new RecommenderCatalog(List.of(), List.of(), Instant.EPOCH);
    }

    /** 곡 ID → 곡. */
    public Map<String, Track> tracksById() {
        Map<String, Track> out = new LinkedHashMap<>();
        for (Track t : tracks) {
            out.put(t.track(), t);
        }
        return out;
    }

    /** 전부 싣는다. {@code next}·{@code daily}·{@code similar} 가 쓴다. */
    public CatalogBody body() {
        return body(s -> true);
    }

    /**
     * 조건을 통과한 구간만 싣는다. 곡은 <b>남은 구간이 있는 것만</b> 싣는다 — 받는 쪽은
     * 구간 없이 온 곡을 거절하지 않지만, 본문만 부풀린다.
     */
    public CatalogBody body(Predicate<Section> keep) {
        List<Section> kept = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Section s : sections) {
            if (keep.test(s)) {
                kept.add(s);
                used.add(s.track());
            }
        }
        List<Track> keptTracks = tracks.stream().filter(t -> used.contains(t.track())).toList();
        return CatalogBody.of(keptTracks, kept);
    }

}
