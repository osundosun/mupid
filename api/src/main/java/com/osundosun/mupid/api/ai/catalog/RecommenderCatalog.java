package com.osundosun.mupid.api.ai.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 추천 후보 한 벌. <b>만들고 나면 바꾸지 않는다.</b>
 *
 * <p><b>쓰는 곳은 조건 어휘 하나다</b>({@link RecommenderFacets}) — 대화 모델에게 고를 수 있는 장르·
 * 악기 값을 알려 준다. 계약 v3(S15P21A204-458)부터 추천 요청 본문에 후보를 싣지 않는다. 추천 서버는
 * 같은 값을 ClickHouse 피처 저장소({@code track_features}·{@code track_sections})에서 읽고, 그 사본의
 * 값 규칙이 여기와 같다({@code TrackFeatureSource}, {@code TrackFeatureMysqlTest}). 예전(2026-09-23 절)
 * 에는 이 객체를 {@code catalog} 본문(약 500KB)으로 매번 실었다.
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
     * @param instruments  이 구간의 악기 코드 이름, 코드 번호순. ClickHouse {@code track_sections.instruments}
     *                     와 같은 값이다 — 추천 서버가 악기 조건을 거기서 건다
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

}
