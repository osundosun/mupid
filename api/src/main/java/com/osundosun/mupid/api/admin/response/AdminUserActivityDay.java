package com.osundosun.mupid.api.admin.response;

import java.time.LocalDate;

/**
 * 날짜 막대 한 칸 — 그날 이 사용자가 남긴 활동의 크기 (S15P21A204-430).
 *
 * <p><b>기록이 없는 날도 들어온다</b>(전부 0). ClickHouse 는 없는 날을 만들어 주지 않지만
 * 서비스가 창을 하루씩 채운다 — 빼면 막대에 구멍이 생겨 "그날은 아무것도 안 했다" 와
 * "그날은 조회 범위 밖" 을 구분할 수 없다.
 *
 * @param date       날짜 (`Asia/Seoul`)
 * @param events     이벤트 수. <b>5초 하트비트를 포함한다</b> — "얼마나 활발했나" 를 재는
 *                   값이라 빼면 오래 들은 날이 작아 보인다. 시간축에 그려지는 항목 수와는
 *                   다르며, 그 차이는 재생 세션 막대가 설명한다
 * @param plays      재생 횟수 (서로 다른 {@code play_id})
 * @param tracks     건드린 서로 다른 곡 수
 * @param listenedMs 실제 청취 시간
 * @param selected   지금 시간축에 펼쳐 놓은 날인가. 화면이 어느 막대를 강조할지 판단하는
 *                   근거를 <b>서버가 준다</b> — 화면이 날짜 문자열을 비교하게 두면 시간대·
 *                   표기가 어긋나는 순간 아무 막대도 강조되지 않는다
 */
public record AdminUserActivityDay(
    LocalDate date,
    long events,
    long plays,
    long tracks,
    long listenedMs,
    boolean selected
) {

}
