package com.osundosun.mupid.api.analytics.sync.dto;

/**
 * 대조의 열쇠 — 곡 번호와 분석 버전.
 *
 * <p>버전을 열쇠에 넣는 이유: ClickHouse 교체는 <b>같은 버전 안에서만</b> 옛 순번을 지운다
 * ({@code TrackSectionRepository}). 재분석으로 버전이 바뀐 곡은 ClickHouse 에 옛 버전 행이
 * 남는데, 곡 단위로만 비교하면 그 곡은 재시도로 고칠 수 없는 불일치로 매일 다시 잡힌다.
 * 버전까지 맞춰 비교하면 MySQL 이 가진 버전만 본다.
 *
 * @param musicId         곡 번호 (ClickHouse {@code track_id})
 * @param analysisVersion 분석 버전
 */
public record TrackVersion(int musicId, String analysisVersion) {

}
