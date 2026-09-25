package com.osundosun.mupid.api.analytics.sync.dto;

/**
 * 한 곡·한 분석 버전의 구간을 요약한 값. 대조 작업이 MySQL 과 ClickHouse 레플리카마다 만들어
 * 서로 비교한다 (S15P21A204-451, 설계 §8.2-4).
 *
 * <p>체크섬은 구간 한 행을 {@code 구간번호|순번|구간UID|라벨|시작ms|끝ms} 로 이어 붙인 문자열의
 * CRC32 를 <b>더한 것</b>이다. 더하기라 행 순서와 무관하고, MySQL 과 ClickHouse 가 같은
 * CRC32(zlib) 를 내므로 양쪽에서 SQL 로 바로 계산된다 — 행을 자바로 끌어오지 않는다.
 * 버전은 열쇠({@link TrackVersion})에 있으므로 문자열에 넣지 않는다.
 *
 * @param sections 구간 수
 * @param checksum 행 CRC32 의 합
 */
public record SectionDigest(long sections, long checksum) {

}
