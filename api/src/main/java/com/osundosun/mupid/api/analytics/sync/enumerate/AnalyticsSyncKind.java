package com.osundosun.mupid.api.analytics.sync.enumerate;

/**
 * 미반영 기록이 가리키는 사본의 종류 (S15P21A204-451).
 *
 * <p>MySQL 이 정본이고 이 종류들은 그 사본이다. 종류마다 "다시 쓰는 쪽" 이 다르다 —
 * {@link #CLICKHOUSE} 는 Spring 재시도 작업이 MySQL 의 현재 구간으로 다시 쓴다.
 * 벡터 사본({@code VECTOR})은 457 에서 더한다. DB 컬럼이 ENUM 이 아니라 {@code VARCHAR(16)}
 * 이라 여기에 값을 더하는 것만으로 된다.
 */
public enum AnalyticsSyncKind {

    /** ClickHouse {@code track_sections_local}. */
    CLICKHOUSE

}
