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
                // 곡 카탈로그는 로그인 전에도 보인다. 첫 방문자가 온보딩 전에
                // 곡을 둘러보기 때문이다. `*` 는 한 세그먼트만 먹으므로
                // `/api/v1/music/{id}/stream` 은 그대로 인증이 필요하다.
                .requestMatchers(HttpMethod.GET, "/api/v1/music").permitAll() // 곡 목록·검색
                .requestMatchers(HttpMethod.GET, "/api/v1/music/*").permitAll() // 곡 상세
                // 아티스트·앨범·가사 조회도 카탈로그의 일부다.
                // `/api/v1/music/*` 는 한 세그먼트만 먹으므로 가사는 따로 적는다.
                .requestMatchers(HttpMethod.GET, "/api/v1/artist", "/api/v1/artist/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/album", "/api/v1/album/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/music/*/lyric").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/music/*/analysis").permitAll()
                // 앨범·아티스트 이미지도 같이 열어야 목록에 표지가 뜬다.
                // 프로필 사진(PROFILE)은 개인 정보라 열지 않는다.
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ALBUM/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/storage/image/ARTIST/*").permitAll()
                // 온보딩 선택지는 마스터 데이터다. 온보딩 화면이 로그인 전에도
                // 떠야 해서 목록만 열어 둔다. 제출·조회는 인증이 필요하다.
                .requestMatchers(HttpMethod.GET, "/api/v1/onboarding/option").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN") // 관리자 전용
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
