package com.osundosun.mupid.api.analytics.properties;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix="clickhouse")
public record ClickHouseProperties(

    String url,

    String username,

    String password,

    @DefaultValue("8") int maxPoolSize,

    @DefaultValue("1") int minIdle,

    @DefaultValue("3s") Duration connectionTimeout,

    @DefaultValue("3s") Duration validationTimeout,

    /**
     * 관리자 콘솔이 프록시할 ClickHouse **HTTP 인터페이스** 주소 (`http://호스트:8123`).
     *
     * 비워 두면 {@link #url} 의 JDBC 주소에서 만들어 쓴다. 같은 서버의 같은 포트라
     * 값을 둘로 나눠 두면 한쪽만 고쳐 놓고 헤매기 좋다. 프록시만 다른 노드로 보내고
     * 싶을 때(예: 샤드 2 를 들여다볼 때)만 채운다.
     */
    String httpUrl,

    /**
     * 콘솔 프록시가 ClickHouse 응답을 기다리는 시간.
     *
     * 운영 nginx 의 `/api/` 는 `proxy_read_timeout 60s` 다. 여기를 그보다 크게 잡으면
     * 브라우저는 nginx 의 504 를 먼저 받고, 스프링은 그 뒤로도 커넥션을 붙들고 있는다.
     */
    @DefaultValue("60s") Duration consoleTimeout

) {

    /**
     * `jdbc:clickhouse://host:8123/db`, `jdbc:ch:https://host:8443/db` 를 둘 다 받는다.
     * 프로토콜이 생략되면 http 다 (ClickHouse JDBC 의 기본값과 같다).
     */
    private static final Pattern JDBC_URL =
        Pattern.compile("^jdbc:(?:clickhouse|ch)(?::(https?))?://([^/?]+)");

    private static final int DEFAULT_HTTP_PORT = 8123;
    private static final int DEFAULT_HTTPS_PORT = 8443;

    /**
     * @return 프록시가 붙을 주소. 끝에 `/` 는 붙지 않는다. 만들 수 없으면 null
     */
    public String httpEndpoint() {
        if (httpUrl != null && !httpUrl.isBlank()) {
            return stripTrailingSlash(httpUrl.trim());
        }
        if (url == null || url.isBlank()) {
            return null;
        }

        Matcher matcher = JDBC_URL.matcher(url.trim());
        if (!matcher.find()) {
            return null;
        }

        String scheme = matcher.group(1) == null ? "http" : matcher.group(1);
        String authority = matcher.group(2);

        // 여러 호스트를 쉼표로 적는 페일오버 표기(`host1:8123,host2:8123`)는 첫 노드만 쓴다.
        int comma = authority.indexOf(',');
        if (comma >= 0) {
            authority = authority.substring(0, comma);
        }

        if (authority.indexOf(':') < 0) {
            int port = "https".equals(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
            authority = "%s:%d".formatted(authority, port);
        }

        return "%s://%s".formatted(scheme, authority);
    }

    /**
     * 주소를 못 만들면 콘솔을 열어 줄 수 없다. 화면이 빈 iframe 을 띄우기 전에 막는다.
     */
    public boolean consoleConfigured() {
        return httpEndpoint() != null;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
