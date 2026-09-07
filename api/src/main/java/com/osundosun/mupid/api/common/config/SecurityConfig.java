package com.osundosun.mupid.api.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.osundosun.mupid.api.auth.filter.AuthFilter;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthFilter authFilter;

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
                .requestMatchers(HttpMethod.POST, "/api/v1/user").permitAll() // 회원가입
                .requestMatchers("/api/v1/user/reset").permitAll() // 패스워드 초기화
                .requestMatchers("/api/v1/mail/verification").permitAll() // 이메일 인증
                .requestMatchers(HttpMethod.GET, "/api/v1/mail/verification/**").permitAll() // 이메일 인증 확인
                .requestMatchers(HttpMethod.GET, "/api/actuator/**").permitAll() // Health Check
                .anyRequest().authenticated())
            .addFilterBefore(
                authFilter,
                UsernamePasswordAuthenticationFilter.class
            )
            .build();
    }
    
}
