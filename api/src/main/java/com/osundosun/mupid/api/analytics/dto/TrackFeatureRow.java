package com.osundosun.mupid.api.analytics.dto;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * ClickHouse {@code track_features} 한 행 — 곡 단위 특징 사본 (S15P21A204-455, 설계 §6.2).
 *
 * <p>값의 정의는 추천 요청 본문 {@code catalog.tracks} 열과 <b>같다</b>
 * ({@code RecommenderCatalogServiceImpl.build}). 서빙이 본문 대신 이 표를 읽어도 순위가 바뀌지
 * 않아야 하므로, 한쪽 규칙을 고치면 다른 쪽도 고친다. 본문과 다른 것은 셋뿐이다 —
 * 본문은 후보만 싣지만 이 표는 <b>모든 곡</b>을 싣고, 그래서 후보 조건을 값으로 들고 있다.
 *
 * <pre>
 *   isDeleted        music.is_deleted — 곡 삭제·복구
 *   playable         DONE 스트림이 있는가 — 스트림 상태
 *   analysisVersion  곡의 구간 분석 버전. 구간이 없으면 빈 문자열
 * </pre>
 *
 * @param trackId         곡 번호 ({@code music_id})
 * @param track           문자열 곡 ID. 첫 구간(시작 시각순) {@code external_id} 의 {@code #} 앞.
 *                        구간이 없으면 빈 문자열
 * @param durationMs      DONE 스트림 길이의 최댓값. 없으면 null
 * @param genres          장르 코드 이름, 코드 번호순
 * @param moods           곡 무드 코드 이름, 코드 번호순
 * @param vocalGender     {@code M}·{@code F}·{@code MIX}. 모르면 null
 */
public record TrackFeatureRow(
    int trackId,
    String track,
    Integer durationMs,
    Double bpm,
    Integer bpmRequestedMin,
    Integer bpmRequestedMax,
    String language,
    String category,
    List<String> genres,
    List<String> moods,
    Boolean hasVocal,
    String vocalGender,
    boolean isDeleted,
    boolean playable,
    String analysisVersion
) {

    public TrackFeatureRow {
        track = track == null ? "" : track;
        genres = genres == null ? List.of() : List.copyOf(genres);
        moods = moods == null ? List.of() : List.copyOf(moods);
        analysisVersion = analysisVersion == null ? "" : analysisVersion;
    }

    /**
     * 대조용 체크섬. 모든 값을 정해진 순서로 이어 붙인 문자열의 CRC32 다.
     *
     * <p>구간 체크섬({@code SectionDigestRepository})과 달리 <b>양쪽 다 자바에서</b> 센다.
     * 곡마다 한 행이라 ClickHouse 에서 행을 끌어와도 가볍고(10만 곡 × 레플리카 2 = 20만 행),
     * SQL 로 두 DB 가 같은 문자열을 만들게 하기가 어렵다 — {@code bpm} 같은 실수의 문자열
     * 표기와 배열 이어 붙이기가 MySQL·ClickHouse 사이에서 다르다. 같은 함수가 두 쪽을 세면
     * 규칙이 어긋날 수 없다.
     *
     * <p>null 과 빈 문자열을 가른다({@code ∅}). 둘은 본문에서 다른 값이다.
     */
    public long digest() {
        StringBuilder text = new StringBuilder(128);
        append(text, trackId);
        append(text, track);
        append(text, durationMs);
        append(text, bpm == null ? null : Double.doubleToLongBits(bpm));
        append(text, bpmRequestedMin);
        append(text, bpmRequestedMax);
        append(text, language);
        append(text, category);
        append(text, String.join(",", genres));
        append(text, String.join(",", moods));
        append(text, hasVocal);
        append(text, vocalGender);
        append(text, isDeleted);
        append(text, playable);
        append(text, analysisVersion);
        CRC32 crc = new CRC32();
        crc.update(text.toString().getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private static void append(StringBuilder text, Object value) {
        text.append(value == null ? "∅" : Objects.toString(value)).append('|');
    }

}
