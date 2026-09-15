package com.osundosun.mupid.api.admin.response;

/**
 * ClickHouse 콘솔을 열었을 때 화면이 받는 것. **열람권 자체는 여기 없다** — 쿠키로 나간다.
 *
 * @param playPath      쿼리 콘솔 주소. iframe 의 src 로 그대로 쓴다
 * @param dashboardPath 차트 대시보드 주소
 * @param expiresIn     열람권이 살아 있는 시간(초). 화면이 만료 전에 다시 연다
 */
public record AdminClickHouseConsoleResponse(

    String playPath,

    String dashboardPath,

    long expiresIn

) {

}
