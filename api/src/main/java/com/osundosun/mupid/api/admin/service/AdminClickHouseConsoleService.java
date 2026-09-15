package com.osundosun.mupid.api.admin.service;

import com.osundosun.mupid.api.admin.dto.AdminConsoleTicket;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * ClickHouse 가 스스로 서빙하는 화면(`/play`·`/dashboard`)을 관리자 화면 안에서 보게 한다.
 *
 * ClickHouse 의 HTTP 포트(8123)를 밖으로 열지 않는 것이 목적이다. 포트를 열면 그 화면에는
 * 우리 로그인이 없고, 방화벽과 ClickHouse 계정이라는 두 번째 자격증명 체계를 따로
 * 관리해야 한다. 대신 스프링이 중간에 서서 **관리자 로그인 하나로** 통과시킨다.
 */
public interface AdminClickHouseConsoleService {

    /**
     * 열람권을 하나 만들어 Redis 에 올린다. 호출자는 `ROLE_ADMIN` 이어야 한다.
     */
    AdminConsoleTicket open();

    /**
     * 열람권을 지운다. 로그아웃할 때 부른다.
     */
    void close(String ticket);

    /**
     * 열람권이 살아 있는가.
     */
    boolean isOpen(String ticket);

    /**
     * 요청 하나를 ClickHouse 로 넘기고 응답을 그대로 돌려준다.
     *
     * 서블릿 타입을 그대로 받는 것은 결과가 얼마나 클지 모르기 때문이다. 바이트 배열로
     * 받아 두면 관리자가 `LIMIT` 없는 `SELECT` 를 한 번 날릴 때 API 서버의 힙이 먼저
     * 죽는다. 통째로 들고 있지 않고 흘려보낸다.
     *
     * @param path `/api/admin/clickhouse/` 뒤에 남은 경로. 빈 문자열이면 쿼리 엔드포인트다
     */
    void forward(String path, HttpServletRequest request, HttpServletResponse response);

}
