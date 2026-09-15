package com.osundosun.mupid.api.admin.controller;

import java.io.IOException;

import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.admin.service.AdminClickHouseConsoleService;
import com.osundosun.mupid.api.admin.service.AdminClickHouseConsoleServiceImpl;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * ClickHouse 화면을 iframe 으로 내보내는 프록시.
 *
 * <h2>왜 `@AdminRole` 이 아닌가</h2>
 * 이 경로로 오는 것은 iframe 의 문서 요청, 그 안의 `<script src>`, 그리고 ClickHouse 화면이
 * 스스로 던지는 `fetch` 다. 셋 다 우리가 `Authorization` 헤더를 붙일 수 없다. `AuthFilter`
 * 는 헤더의 Bearer 만 보므로 (`SecurityConfig` 주석 참고) 스프링 시큐리티에 맡기면
 * 관리자도 늘 401 이다.
 *
 * 그래서 `SecurityConfig` 에서는 열어 두고 **여기서 쿠키를 본다.** 관리자 화면(`/api/admin`)
 * 의 HTML 을 열어 둔 것과 같은 판단처럼 보이지만 성격이 다르다 — 그쪽 HTML 에는 데이터가
 * 없어서 열려 있어도 잃을 게 없지만, 여기는 분석 DB 그 자체다. 쿠키 검사가 **유일한
 * 방어선**이고, 이 검사를 무르게 하면 ClickHouse 가 통째로 공개된다.
 *
 * 열람권은 `POST /api/v1/admin/clickhouse/console` 이 발급한다. 그쪽은 `@AdminRole` 이라
 * 관리자만 쿠키를 받을 수 있다.
 */
@RestController
@RequiredArgsConstructor
public class AdminClickHouseController {

    /**
     * 열람권 쿠키 이름. `Path` 가 `/api/admin/clickhouse` 라 다른 요청에는 실리지 않는다.
     */
    public static final String TICKET_COOKIE = "mupid_ch_console";

    private final AdminClickHouseConsoleService consoleService;

    @RequestMapping(
        value = {"/api/admin/clickhouse", "/api/admin/clickhouse/**"},
        method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS}
    )
    public void proxy(
        @CookieValue(name = TICKET_COOKIE, required = false) String ticket,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (!consoleService.isOpen(ticket)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("text/html;charset=UTF-8");
            try {
                response.getWriter().write(EXPIRED_PAGE);
            } catch (IOException e) {
                // 브라우저가 이미 끊은 경우다. 남길 것이 없다.
            }
            return;
        }

        consoleService.forward(remainingPath(request), request, response);
    }

    /**
     * `/api/admin/clickhouse/` 뒤에 남은 부분. 어디로 넘길지는 서비스가 정한다.
     *
     * 퍼센트 디코딩을 하지 않는다. 넘길 수 있는 경로는 `play`·`dashboard`·`js/<파일>`
     * 뿐이라 디코딩할 것이 애초에 없고, 디코딩하면 `%2e%2e` 가 `..` 로 살아난다.
     */
    private String remainingPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = AdminClickHouseConsoleServiceImpl.PREFIX;
        if (uri.startsWith(prefix)) {
            return uri.substring(prefix.length());
        }
        return "";
    }

    /**
     * iframe 안에 뜨는 안내. 여기서 JSON 을 주면 관리자는 하얀 화면만 본다.
     */
    private static final String EXPIRED_PAGE = """
        <!DOCTYPE html>
        <html lang="ko"><head><meta charset="utf-8"><title>세션 만료</title>
        <style>
        body { margin: 0; display: grid; place-items: center; height: 100vh;
               background: #0f1115; color: #8b93a7;
               font: 13px/1.6 -apple-system, "Segoe UI", "Malgun Gothic", sans-serif; }
        strong { color: #e6e8ee; display: block; margin-bottom: 6px; font-size: 15px; }
        </style></head>
        <body><div style="text-align:center">
        <strong>콘솔 세션이 만료되었습니다</strong>
        관리자 화면에서 &lsquo;다시 열기&rsquo; 를 누르세요.
        </div></body></html>
        """;

}
