package com.osundosun.mupid.api.auth.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.auth.dto.AuthLoginResult;
import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.auth.dto.TokenPair;
import com.osundosun.mupid.api.auth.request.AuthLoginRequest;
import com.osundosun.mupid.api.auth.response.AuthLoginResponse;
import com.osundosun.mupid.api.auth.response.AuthReissueTokenResponse;
import com.osundosun.mupid.api.auth.service.AuthService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
   
    private final AuthService authService;
    
    @PostMapping
    public ResponseEntity<BaseResponse<AuthLoginResponse>> login(
        @Valid @RequestBody AuthLoginRequest request
    ) {
        // 로그인
        AuthLoginResult result = authService.login(
            request.email(), request.password(), request.refreshTokenValidityDays());
        User user = result.user();

        // 리프레시 토큰 설정
        ResponseCookie cookie = ResponseCookie
            .from("refresh_token", result.refreshToken())
            .sameSite("Strict")
            .httpOnly(true)
            .secure(true)
            // 쿠키가 먼저 사라지면 토큰이 살아 있어도 재발급을 못 한다.
            .maxAge(result.refreshTokenValidity())
            .build();

        // 액세스 토큰 설정
        AuthLoginResponse response = new AuthLoginResponse(
            result.accessToken(),
            user.getUserId(),
            user.getRole().name()
        );

        // 응답 전송
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(BaseResponse.of(response));
    }

    /**
     * 액세스 토큰 재발급.
     *
     * 쿠키가 아예 없으면 **401** 이다. `required = true` 로 두면 스프링이
     * `MissingRequestCookieException` 을 던지고 그게 500 으로 나가는데,
     * 로그인한 적 없는 첫 방문자가 늘 밟는 길이라 서버가 고장 난 것처럼 보인다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<AuthReissueTokenResponse>> reissueToken(
        @CookieValue(name = "refresh_token", required = false) String refreshToken
    ) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }

        // 토큰 재발급
        TokenPair tokenPair = authService.reissueToken(refreshToken);

        // 리프레시 토큰 설정
        ResponseCookie cookie = ResponseCookie
            .from("refresh_token", tokenPair.refreshToken())
            .sameSite("Strict")
            .httpOnly(true)
            .secure(true)
            // 쿠키가 먼저 사라지면 토큰이 살아 있어도 재발급을 못 한다. 회전하지 않는
            // ROLE_SYSTEM 은 같은 토큰이 돌아오므로 남은 수명만큼만 준다.
            .maxAge(Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt()))
            .build();

        // 액세스 토큰 설정
        AuthReissueTokenResponse response = new AuthReissueTokenResponse(tokenPair.accessToken());

        // 응답 전송
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(BaseResponse.of(response));
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> logout(
        Authentication auth,
        @CookieValue(name = "refresh_token") String refreshToken
    ) {
        String accessToken = String.valueOf(auth.getCredentials());
        authService.logout(List.of(accessToken, refreshToken));
        return ResponseEntity.ok(
            BaseResponse.message("로그아웃 처리되었습니다.")
        );
    }

}
