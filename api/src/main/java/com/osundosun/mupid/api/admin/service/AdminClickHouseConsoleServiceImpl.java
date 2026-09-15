package com.osundosun.mupid.api.admin.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.osundosun.mupid.api.admin.dto.AdminConsoleTicket;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.analytics.properties.ClickHouseProperties;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.utils.SecurityUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ClickHouse 화면 프록시.
 *
 * <h2>왜 그냥 포트를 열지 않는가</h2>
 * ClickHouse 의 8123 을 밖으로 내면 `/play` 와 `/dashboard` 가 바로 열린다. 다만 그
 * 화면에는 우리 로그인이 없다. 지키는 것이 ClickHouse 계정 하나뿐이고, 그 계정은
 * 분석 DB 를 통째로 읽고 쓴다. 포트를 닫아 두고 스프링만 통과시키면 지켜야 할 문이
 * 이미 지키고 있는 관리자 로그인 하나로 줄어든다.
 *
 * <h2>iframe 안에서 도는 것은 우리 코드가 아니다</h2>
 * `/play` 는 ClickHouse 가 만든 자기완결 HTML 이고, 자기가 서빙된 **오리진의 루트**로
 * 쿼리를 던진다 (`location.origin + '?query...'`). 그대로 두면 스프링의 `/` 로 가서
 * 404 가 된다. 그래서 내려보내기 전에 `<head>` 에 작은 조각을 끼워 `fetch` 를 한 번
 * 감싼다 — 같은 오리진으로 나가는 요청의 경로를 `/api/admin/clickhouse/` 로 바꾼다.
 *
 * ClickHouse 의 HTML·JS 를 문자열로 고치는 것은 버전이 바뀌면 깨질 수 있는 방식이다.
 * 그래서 고치는 곳을 둘로만 묶었다 — `</head>` 앞에 끼워 넣기, `dashboard` 가 읽는
 * `/js/*` 를 상대 경로로 바꾸기. 나머지는 손대지 않는다.
 *
 * <h2>자격증명은 브라우저로 내려가지 않는다</h2>
 * 두 화면 모두 user·password 입력칸을 갖고 있고 그 값을 쿼리 문자열에 실어 보낸다.
 * 프록시는 그 두 파라미터를 **버리고** `X-ClickHouse-User`·`X-ClickHouse-Key` 헤더를
 * 서버에서 붙인다. 둘을 같이 보내면 ClickHouse 가 인증을 거부하기도 하거니와,
 * ClickHouse 비밀번호가 브라우저 주소창과 로그에 남는 것을 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminClickHouseConsoleServiceImpl implements AdminClickHouseConsoleService {

    /**
     * 열람권 수명. 짧으면 관리자가 쿼리를 쓰는 중에 끊기고, 길면 쿠키가 새어 나갔을 때
     * 오래 산다. 화면은 이 시간이 지나기 전에 조용히 다시 연다.
     */
    public static final Duration TICKET_TTL = Duration.ofMinutes(30);

    /**
     * 프록시의 뿌리. iframe 안에서 도는 조각도 이 값을 그대로 쓴다.
     */
    public static final String PREFIX = "/api/admin/clickhouse/";

    public static final String PLAY_PATH = PREFIX + "play";
    public static final String DASHBOARD_PATH = PREFIX + "dashboard";

    /**
     * 넘겨줄 경로는 손으로 적은 것만이다.
     *
     * `**` 로 받아 그대로 이어 붙이면 ClickHouse 의 HTTP 인터페이스 전체가 열린다.
     * 거기에는 `/replicas_status` 처럼 읽기 전용인 것도 있지만, 경로를 검사하지 않는
     * 프록시는 나중에 누가 무엇을 덧붙일지 모르는 구멍이 된다.
     */
    private static final Set<String> ALLOWED_PAGES = Set.of("play", "dashboard");

    /**
     * `dashboard` 가 읽는 차트 라이브러리 (`/js/uplot.js`, `/js/lz-string.js`).
     */
    private static final Pattern ALLOWED_ASSET = Pattern.compile("^js/[A-Za-z0-9._-]+$");

    /**
     * 브라우저가 보내온 것을 버리고 서버 값으로 덮는 파라미터.
     */
    private static final Set<String> STRIPPED_PARAMS = Set.of("user", "password", "quota_key");

    /**
     * ClickHouse 응답에서 브라우저까지 그대로 넘기는 헤더.
     *
     * 화이트리스트인 이유: 업스트림이 `X-Frame-Options` 같은 것을 붙이기 시작하면
     * iframe 이 소리 없이 하얗게 된다. 또 `Content-Length`·`Transfer-Encoding` 은
     * 우리가 본문을 고쳐 쓰므로 그대로 넘기면 틀린 값이 된다.
     */
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of(
        HttpHeaders.CONTENT_TYPE,
        "X-ClickHouse-Format",
        "X-ClickHouse-Summary",
        "X-ClickHouse-Query-Id",
        "X-ClickHouse-Timezone",
        "X-ClickHouse-Exception-Code"
    );

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClickHouseProperties props;
    private final CacheService cacheService;
    private final RestClient clickHouseConsoleRestClient;

    // -------------------------------------------------------------------- 열람권

    @Override
    public AdminConsoleTicket open() {
        if (!props.consoleConfigured()) {
            log.warn("ClickHouse 콘솔 주소를 만들 수 없다 url={} http-url={}", props.url(), props.httpUrl());
            throw new BusinessException(AdminErrorCode.CONSOLE_NOT_CONFIGURED);
        }

        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(seed);

        User admin = SecurityUtils.getAuthUser();
        String owner = admin == null ? "-" : admin.getEmail();

        cacheService.set(ticket, owner, CacheKind.ClickHouseConsole, TICKET_TTL);
        log.info("ClickHouse 콘솔을 열었다 admin={} endpoint={}", owner, props.httpEndpoint());

        return new AdminConsoleTicket(ticket, TICKET_TTL);
    }

    @Override
    public void close(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return;
        }
        cacheService.delete(ticket, CacheKind.ClickHouseConsole);
    }

    @Override
    public boolean isOpen(String ticket) {
        return ticket != null
            && !ticket.isBlank()
            && cacheService.exists(ticket, CacheKind.ClickHouseConsole);
    }

    // ---------------------------------------------------------------------- 프록시

    @Override
    public void forward(String path, HttpServletRequest request, HttpServletResponse response) {
        String endpoint = props.httpEndpoint();
        if (endpoint == null) {
            write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "ClickHouse 주소가 설정되어 있지 않습니다.");
            return;
        }
        if (!isAllowedPath(path)) {
            write(response, HttpServletResponse.SC_NOT_FOUND,
                "이 경로는 콘솔에서 열 수 없습니다.");
            return;
        }

        URI target;
        try {
            String query = filterQuery(request.getQueryString());
            target = URI.create(endpoint + "/" + path + (query == null ? "" : "?" + query));
        } catch (IllegalArgumentException e) {
            write(response, HttpServletResponse.SC_BAD_REQUEST, "요청 주소를 해석하지 못했습니다.");
            return;
        }

        try {
            exchange(target, path, request, response);
        } catch (RestClientException | IOException e) {
            // ClickHouse 가 꺼져 있을 때 매번 밟는 길이라 스택은 남기지 않는다.
            log.warn("ClickHouse 콘솔 프록시 실패 target={} 사유={}", target, e.getMessage());
            write(response, HttpServletResponse.SC_BAD_GATEWAY,
                "ClickHouse 에 닿지 못했습니다: " + e.getMessage());
        }
    }

    private void exchange(URI target, String path, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        byte[] body = method == HttpMethod.POST ? request.getInputStream().readAllBytes() : new byte[0];

        RestClient.RequestBodySpec spec = clickHouseConsoleRestClient
            .method(method)
            .uri(target)
            .header("X-ClickHouse-User", orEmpty(props.username()))
            .header("X-ClickHouse-Key", orEmpty(props.password()))
            // 압축은 쓰지 않는다. 업스트림이 gzip 으로 주면 본문을 고쳐 쓸 수 없고
            // (play·dashboard 는 HTML 을 손봐서 내려보낸다), 서블릿으로 그대로 흘리면
            // Content-Encoding 과 실제 바이트가 어긋나기 쉽다. 같은 네트워크 안이다.
            .header(HttpHeaders.ACCEPT_ENCODING, "identity");

        if (body.length > 0) {
            spec.contentType(contentTypeOf(request));
            spec.body(body);
        }

        boolean rewritable = ALLOWED_PAGES.contains(path);

        spec.exchange((upstreamRequest, upstream) -> {
            response.setStatus(upstream.getStatusCode().value());
            FORWARDED_RESPONSE_HEADERS.forEach(name -> {
                String value = upstream.getHeaders().getFirst(name);
                if (value != null) {
                    response.setHeader(name, value);
                }
            });
            if (path.endsWith(".js")) {
                // ClickHouse 는 `/js/*` 도 text/html 로 내보낸다. 지금 브라우저는 그래도
                // 실행하지만, 언젠가 막히면 증상이 "차트만 안 뜬다" 라 찾기 어렵다.
                response.setHeader(HttpHeaders.CONTENT_TYPE, "text/javascript;charset=UTF-8");
            }

            try (InputStream in = upstream.getBody()) {
                if (rewritable) {
                    String html = rewrite(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    response.setContentLength(bytes.length);
                    response.getOutputStream().write(bytes);
                } else {
                    try (OutputStream out = response.getOutputStream()) {
                        in.transferTo(out);
                    }
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ 경로·질의

    private boolean isAllowedPath(String path) {
        // 빈 경로가 쿼리 엔드포인트다. ClickHouse 의 HTTP 인터페이스는 경로를 쓰지 않는다.
        return path.isEmpty()
            || ALLOWED_PAGES.contains(path)
            || ALLOWED_ASSET.matcher(path).matches();
    }

    /**
     * 브라우저가 보낸 질의 문자열에서 자격증명만 빼고 **글자 그대로** 넘긴다.
     *
     * 파싱해서 다시 조립하지 않는 이유: `query` 파라미터에는 SQL 이 통째로 퍼센트
     * 인코딩되어 들어 있다. 한 번 디코딩했다 다시 인코딩하면 원본과 달라질 여지가
     * 생기고, 그 차이는 쿼리가 틀리게 도는 형태로만 드러난다.
     */
    private String filterQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        StringBuilder kept = new StringBuilder();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            if (STRIPPED_PARAMS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(pair);
        }

        return kept.isEmpty() ? null : kept.toString();
    }

    // -------------------------------------------------------------------- HTML 손질

    /**
     * iframe 안에서 먼저 도는 조각.
     *
     * 1. 같은 오리진으로 나가는 `fetch` 의 경로를 프록시 아래로 옮긴다. ClickHouse 의
     *    화면은 `new URL(주소).origin + '?query'` 처럼 **경로를 지우고** 부르는 곳이
     *    있어서, 주소칸을 고쳐 주는 것만으로는 부족하다.
     * 2. user·password 칸을 잠근다. 값을 적어도 프록시가 버리므로, 열어 두면 적은
     *    값이 조용히 무시되는 화면이 된다.
     * 3. `<base target="_blank">` — 화면 아래쪽 ClickHouse 홈페이지 링크를 누르면
     *    iframe 안이 통째로 바깥 사이트가 되는 것을 막는다.
     */
    private static final String SHIM = """
        <base target="_blank">
        <script>
        (function () {
            'use strict';
            var PREFIX = '/api/admin/clickhouse/';
            var original = window.fetch;
            window.fetch = function (input, init) {
                try {
                    var raw = typeof input === 'string' ? input
                        : (input instanceof URL ? input.href : null);
                    if (raw) {
                        var target = new URL(raw, window.location.href);
                        if (target.origin === window.location.origin
                            && target.pathname.indexOf(PREFIX) !== 0) {
                            target.pathname = PREFIX;
                            input = target.toString();
                        }
                    }
                } catch (error) {
                    /* 주소를 해석하지 못하면 원본 그대로 보낸다. */
                }
                return original.call(window, input, init);
            };
            window.addEventListener('load', function () {
                ['user', 'password'].forEach(function (id) {
                    var field = document.getElementById(id);
                    if (!field) { return; }
                    field.value = '';
                    field.disabled = true;
                    field.placeholder = '서버가 대신 인증';
                    field.title = 'ClickHouse 자격증명은 API 서버가 붙입니다. 브라우저로 내려가지 않습니다.';
                });
            });
        }());
        </script>
        """;

    private String rewrite(String html) {
        // dashboard 는 차트 라이브러리를 `/js/uplot.js` 로 읽는다. 오리진 루트라
        // 프록시 밖으로 나가므로 상대 경로로 내린다 -> /api/admin/clickhouse/js/uplot.js
        String rewritten = html.replace("src=\"/js/", "src=\"js/");

        int head = rewritten.lastIndexOf("</head>");
        if (head < 0) {
            // 여기 오면 ClickHouse 가 화면 구조를 바꾼 것이다. 조각 없이 내보내면
            // 쿼리가 스프링 루트로 날아가 화면이 조용히 죽으므로 남겨 둔다.
            log.warn("ClickHouse 화면에 </head> 가 없어 프록시 조각을 넣지 못했다");
            return rewritten;
        }
        return rewritten.substring(0, head) + SHIM + rewritten.substring(head);
    }

    // ------------------------------------------------------------------------ 기타

    private String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 브라우저가 이상한 `Content-Type` 을 보내도 500 으로 번지지 않게 한다. 쿼리 본문은
     * 어차피 SQL 평문이라 잘못 짚어도 ClickHouse 가 읽는 데 지장이 없다.
     */
    private MediaType contentTypeOf(HttpServletRequest request) {
        String raw = request.getContentType();
        if (raw == null || raw.isBlank()) {
            return MediaType.TEXT_PLAIN;
        }
        try {
            return MediaType.parseMediaType(raw);
        } catch (InvalidMediaTypeException e) {
            return MediaType.TEXT_PLAIN;
        }
    }

    private void write(HttpServletResponse response, int status, String message) {
        // ClickHouse 화면은 실패 응답의 본문을 그대로 화면에 찍는다. BaseResponse 로
        // 감싸면 관리자가 JSON 껍데기를 읽게 되므로 평문으로 내보낸다.
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        try {
            response.getWriter().write(message);
        } catch (IOException e) {
            log.warn("ClickHouse 콘솔 오류 응답을 쓰지 못했다 사유={}", e.getMessage());
        }
    }

}
