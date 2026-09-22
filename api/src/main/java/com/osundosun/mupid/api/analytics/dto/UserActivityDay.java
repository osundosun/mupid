package com.osundosun.mupid.api.analytics.dto;

/**
 * 사용자 한 명의 **하루치 활동 집계** (S15P21A204-430).
 *
 * <p>관리자 화면의 날짜 막대가 이 값을 읽는다. 어느 날에 무엇이 있었는지 한눈에 보이면
 * 그 날을 눌러 시간축으로 내려간다 — 날짜를 손으로 짚어 가며 찾지 않아도 된다.
 *
 * <p><b>기록이 없는 날은 여기 나오지 않는다.</b> ClickHouse 가 없는 날을 만들어 주지
 * 않기 때문이다. 화면이 날마다 막대를 그리므로 호출부가 빈 날을 0 으로 채운다 —
 * 빼 버리면 "기록 없음" 과 "아직 안 불러옴" 을 구분할 수 없다
 * ({@code ReportDailyRangeResponse} 가 같은 이유로 빈 날을 채운다).
 *
 * @param date       날짜 (`Asia/Seoul` 기준). `YYYY-MM-DD`
 * @param events     그날 이벤트 수. `PLAY_PROGRESS` 하트비트를 <b>포함한다</b> —
 *                   "얼마나 활발했는가" 를 재는 값이라 빼면 실제 활동이 작아 보인다
 * @param plays      재생 횟수. {@code play_id} 가 영(zero) UUID 가 아닌 것의 개수다.
 *                   `SEARCH` 처럼 재생 밖의 이벤트에는 `play_id` 가 없고 적재기가 그것을
 *                   영 UUID 로 넣는데, 그대로 세면 <b>검색만 한 날에도 재생 1회</b>가 된다
 * @param tracks     그날 건드린 서로 다른 곡 수
 * @param listenedMs 실제 청취 시간. `PLAY_PROGRESS` 의 `[from_ms, position_ms)` 합이다
 */
public record UserActivityDay(
    String date,
    long events,
    long plays,
    long tracks,
    long listenedMs
) {

}
