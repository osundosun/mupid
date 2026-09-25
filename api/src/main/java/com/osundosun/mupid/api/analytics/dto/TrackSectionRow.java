package com.osundosun.mupid.api.analytics.dto;

/**
 * ClickHouse {@code track_sections} 한 행. MySQL {@code music_section_analysis} 를
 * 그대로 옮긴 것이다 (S15P21A204-435).
 *
 * <p>이름이 다른 것은 셋뿐이고, 값은 같다. ClickHouse 에는 FK 제약이 없으므로
 * {@code trackId} 에 곡 번호를 그대로 넣는다.
 *
 * <pre>
 *   trackId                 ← music_id
 *   musicSectionAnalysisId  ← music_section_analysis_id (PK)
 *   sectionIndex            ← `index`
 *   sectionUid              ← external_id
 *   fn                      ← section_label
 *   startMs / endMs         ← start_time_ms / end_time_ms
 * </pre>
 *
 * <p>{@code analysisVersion} 은 MySQL 에 없다. 요청이 실어 오지만 구간 표에 버전
 * 컬럼이 없어 저장되지 않는 값인데({@code MusicSectionBulkRequest}), ClickHouse 에는
 * 컬럼이 있고 {@code v_track_sections_current} 가 그것으로 현재 버전을 고른다.
 * <b>딕셔너리가 보는 값과 다른 버전을 넣으면 표에는 들어가되 구간 판정에 쓰이지 않는다.</b>
 *
 * @param trackId                곡 번호
 * @param musicSectionAnalysisId 구간 번호. 분석 결과를 MySQL 구간 행에 되짚는 열쇠다
 * @param analysisVersion        분석 버전. 현재 {@code v1}
 * @param sectionIndex           곡 안 순번
 * @param sectionUid             AI 문자열 구간 ID({@code T000148#3}). 추천·신호가 쓰는 키
 * @param fn                     구간 종류({@code INTRO}·{@code CHORUS}…)
 * @param startMs                구간 시작
 * @param endMs                  구간 끝
 * @param energy                 구간 RMS(0~1). 모르면 null. 서빙 특징 조회가 요청 본문
 *                               {@code catalog.sections.energy} 대신 읽는다 (S15P21A204-455)
 */
public record TrackSectionRow(
    int trackId,
    int musicSectionAnalysisId,
    String analysisVersion,
    int sectionIndex,
    String sectionUid,
    String fn,
    int startMs,
    int endMs,
    Double energy
) {

}
