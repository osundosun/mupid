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

import com.osundosun.mupid.api.auth.exception.AuthForbiddenHandler;
import com.osundosun.mupid.api.auth.exception.AuthUnauthorizedHandler;
import com.osundosun.mupid.api.auth.filter.AuthFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthFilter authFilter;
    private final AuthUnauthorizedHandler authUnauthorizedHandler;
    private final AuthForbiddenHandler authForbiddenHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity httpSecurity) {
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
                 * 다만 **앨범·아티스트 이미지는 연다.** `<img src>` 에는
                 * Authorization 헤더를 붙일 수 없어서, 닫으면 목록의 표지가 전부
                 * 깨진다. 키가 앨범·아티스트 이름이라 개인 정보도 아니다.
                 * 프로필 사진(PROFILE)은 개인 정보라 열지 않는다 — 그쪽은 토큰을
                 * 붙여 바이트를 받아 object URL 로 쓴다(`lib/profile-photo.ts`).
                 */
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ALBUM/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ARTIST/*").permitAll()
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
                .anyRequest().authenticated())
            .addFilterBefore(
                authFilter,
                UsernamePasswordAuthenticationFilter.class
            )
            .exceptionHandling(it -> it
                .authenticationEntryPoint(authUnauthorizedHandler) // 401
                .accessDeniedHandler(authForbiddenHandler) // 403
            )
            .build();
    }
    
}
