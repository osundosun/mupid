package com.osundosun.mupid.api.analytics.sync.enumerate;

/**
 * 미반영 기록이 가리키는 사본의 종류 (S15P21A204-451).
 *
 * <p>MySQL 이 정본이고 이 종류들은 그 사본이다. 종류마다 "다시 쓰는 쪽" 이 다르다 —
 * {@link #CLICKHOUSE} 는 Spring 재시도 작업이 MySQL 의 현재 구간으로 다시 쓴다.
 * 벡터 사본({@code VECTOR})은 457 에서 더한다. DB 컬럼이 ENUM 이 아니라 {@code VARCHAR(16)}
 * 이라 여기에 값을 더하는 것만으로 된다.
 *
 * <h2>구간과 곡 특징은 종류를 나눈다 (S15P21A204-455)</h2>
 *
 * <p>한 종류로 묶으면 장르 한 줄을 고쳐도 구간 교체까지 돌고, 구간을 바꾸는 요청도 곡 특징을
 * 다시 쓴다. 더 나쁜 것은 <b>실패가 서로를 막는다</b>는 점이다 — 한 곡의 구간 값이 ClickHouse
 * 형에 안 맞아 계속 실패하면 그 곡의 삭제 표시까지 못 간다. 원천도 다르다(구간은
 * {@code music_section_analysis} 하나, 특징은 곡·분석·장르·무드·스트림·구간 다섯 표). revision 은
 * (곡, 종류) 단위라 겹친 요청의 판단(`TrackSectionSync` "왜 revision 인가")이 종류마다 그대로 선다.
 * 구간 교체는 특징의 {@code track}·{@code analysis_version} 도 바꾸므로 두 종류를 함께 올린다.
 */
public enum AnalyticsSyncKind {

    /** ClickHouse {@code track_sections_local}. */
    CLICKHOUSE,

    /** ClickHouse {@code track_features_local} — 곡 단위 특징 사본 (S15P21A204-455). */
    TRACK_FEATURES

}
