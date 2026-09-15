package com.osundosun.mupid.api.admin.dto;

import java.time.Duration;

/**
 * ClickHouse 콘솔 열람권.
 *
 * 응답 본문이 아니라 **쿠키**로 나간다. iframe 안에서 도는 것은 ClickHouse 가 만든
 * 화면이라 우리가 헤더를 붙일 수 없고, 그 화면이 스스로 던지는 `fetch` 까지 한 번에
 * 통과시킬 수 있는 자격증명은 쿠키뿐이다.
 *
 * @param value 무작위 토큰. Redis 에 있을 때만 유효하다
 * @param ttl   남은 유효 시간
 */
public record AdminConsoleTicket(

    String value,

    Duration ttl

) {

}
