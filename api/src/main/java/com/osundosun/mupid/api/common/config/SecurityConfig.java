package com.osundosun.mupid.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.osundosun.mupid.api.auth.exception.AuthForbiddenHandler;
import com.osundosun.mupid.api.auth.exception.AuthUnauthorizedHandler;
import com.osundosun.mupid.api.auth.filter.AuthFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * ClickHouse 콘솔 프록시. iframe 으로 띄워야 해서 X-Frame-Options 를 따로 준다.
     */
    private static final String CLICKHOUSE_CONSOLE = "/api/admin/clickhouse/**";

    private final AuthFilter authFilter;
    private final AuthUnauthorizedHandler authUnauthorizedHandler;
    private final AuthForbiddenHandler authForbiddenHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) {
        RequestMatcher console = PathPatternRequestMatcher.pathPattern(CLICKHOUSE_CONSOLE);

        return httpSecurity
            .csrf(it -> it.disable())
            .httpBasic(it -> it.disable())
            .formLogin(it -> it.disable())
            .sessionManagement(it ->
                it.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(it -> it
                .requestMatchers(HttpMethod.POST, "/api/v1/auth").permitAll() // 로그인
                // 액세스 토큰이 만료된 뒤에 부르는 엔드포인트라 액세스 토큰을 요구하면 안 된다.
                // 자격증명은 쿠키의 리프레시 토큰이고, AuthService 가 검증·차단목록 확인을 한다.
                .requestMatchers(HttpMethod.GET, "/api/v1/auth").permitAll() // 토큰 재발급
                .requestMatchers(HttpMethod.POST, "/api/v1/user").permitAll() // 회원가입
                .requestMatchers("/api/v1/user/reset").permitAll() // 패스워드 초기화
                .requestMatchers("/api/v1/mail/verification").permitAll() // 이메일 인증
                .requestMatchers(HttpMethod.GET, "/api/v1/mail/verification/**").permitAll() // 이메일 인증 확인
                .requestMatchers(HttpMethod.GET, "/api/actuator/**").permitAll() // Health Check
                /*
                 * 곡 카탈로그(목록·상세·아티스트·앨범·가사·분석)는 **열지 않는다.**
                 *
                 * 한때 열어 두었던 까닭은 첫 방문자가 로그인 전에 곡을 둘러본다고
                 * 보았기 때문이다. 지금은 로그인하지 않으면 서비스를 쓸 수 없고
                 * (`frontend/lib/routes.ts` 의 publicPaths 는 `/`·`/login`·`/signup`
                 * 뿐이다) 데모 곡도 없앴다. 비로그인이 곡을 보는 화면 자체가 없으니
                 * 열어 둘 이유가 사라졌다. 열어 두면 곡·가사·분석이 통째로 공개된다.
                 *
                 * 다만 **앨범·아티스트·곡 커버 이미지는 연다.** `<img src>` 에는
                 * Authorization 헤더를 붙일 수 없어서, 닫으면 목록의 표지가 전부
                 * 깨진다. 키가 앨범·아티스트 이름이거나 곡 번호라 개인 정보도 아니다.
                 * 프로필 사진(PROFILE)은 개인 정보라 열지 않는다 — 그쪽은 토큰을
                 * 붙여 바이트를 받아 object URL 로 쓴다(`lib/profile-photo.ts`).
                 */
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ALBUM/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ARTIST/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/TRACK/*").permitAll()
                // 온보딩 선택지(무드·장르 이름)는 마스터 데이터다. 지금은 온보딩
                // 화면이 로그인한 사람에게만 뜨므로 닫아도 되지만, 개인 정보가
                // 아니고 가입 흐름을 건드리지 않으려 열어 둔다. 제출·조회는
                // 인증이 필요하다.
                .requestMatchers(HttpMethod.GET, "/api/v1/onboarding/option").permitAll()
                /*
                 * 관리자 화면의 **HTML 골격만** 연다 (`AdminController`).
                 *
                 * 브라우저 주소창으로 들어오는 요청에는 Authorization 헤더를 붙일 수
                 * 없고, `AuthFilter` 는 헤더의 Bearer 토큰만 본다. 그래서 이 경로를
                 * 닫아 두면 관리자도 화면에 들어올 수 없다 — 늘 401 이다.
                 *
                 * 대신 이 HTML 에는 데이터가 한 줄도 없다. 화면이 fetch 로 부르는
                 * `/api/v1/admin/**` 는 아래 anyRequest().authenticated() 에 걸리고
                 * `AdminApiController` 의 @AdminRole 이 ROLE_ADMIN 을 요구한다.
                 * 실제 방어선은 그쪽 하나다.
                 */
                .requestMatchers(HttpMethod.GET, "/api/admin", "/api/admin/login").permitAll()
                /*
                 * ClickHouse 콘솔 프록시 (`AdminClickHouseController`).
                 *
                 * 여기로 오는 것은 iframe 의 문서 요청, 그 안의 `<script src>`, 그리고
                 * ClickHouse 가 만든 화면이 스스로 던지는 `fetch` 다. 셋 다 우리가
                 * Authorization 헤더를 붙일 수 없으므로 위와 같은 이유로 열어 둔다.
                 *
                 * ⚠ 위의 관리자 HTML 과 달리 **이 경로는 데이터가 나간다.** 열람권
                 * 쿠키를 보는 컨트롤러가 유일한 방어선이고, 쿠키는 `@AdminRole` 이
                 * 걸린 `POST /api/v1/admin/clickhouse/console` 만 발급한다.
                 */
                .requestMatchers(CLICKHOUSE_CONSOLE).permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(
                authFilter,
                UsernamePasswordAuthenticationFilter.class
            )
            /*
             * X-Frame-Options 는 기본이 DENY 다. 그대로 두면 ClickHouse 콘솔 iframe 이
             * **아무것도 그리지 않는다** — 브라우저가 프레임을 거부하고, 콘솔 오류
             * 말고는 아무 표시도 남지 않아 원인을 찾기 어렵다.
             *
             * 그렇다고 전체를 SAMEORIGIN 으로 내리지는 않는다. 운영에서 프런트엔드와
             * API 는 같은 오리진이라(nginx 가 둘 다 서빙한다) 프런트에 HTML 주입이
             * 생기면 관리자 로그인 화면이 프레임에 걸릴 수 있다. 그래서 기본 라이터를
             * 끄고, 콘솔 경로에만 SAMEORIGIN 을, 나머지에는 DENY 를 직접 붙인다.
             */
            .headers(it -> it
                .frameOptions(frame -> frame.disable())
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                    console,
                    new XFrameOptionsHeaderWriter(XFrameOptionsMode.SAMEORIGIN)))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                    new NegatedRequestMatcher(console),
                    new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)))
            )
            .exceptionHandling(it -> it
                .authenticationEntryPoint(authUnauthorizedHandler) // 401
                .accessDeniedHandler(authForbiddenHandler) // 403
            )
            .build();
    }
    
}
