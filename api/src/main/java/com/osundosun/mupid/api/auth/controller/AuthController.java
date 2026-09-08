package com.osundosun.mupid.api.auth.controller;

import java.time.Duration;
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
import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.auth.request.AuthLoginRequest;
import com.osundosun.mupid.api.auth.response.AuthLoginResponse;
import com.osundosun.mupid.api.auth.response.AuthReissueAccessTokenResponse;
import com.osundosun.mupid.api.auth.service.AuthService;
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
        AuthLoginResult result = authService.login(request.email(), request.password());
        User user = result.user();

        // 리프레시 토큰 설정
        ResponseCookie cookie = ResponseCookie
            .from("refresh_token", result.refreshToken())
            .sameSite("Strict")
            .httpOnly(true)
            .secure(true)
            .maxAge(Duration.ofMillis(JwtProvider.ACCESS_TOKEN_VALIDITY_MS))
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

    @GetMapping
    public ResponseEntity<BaseResponse<AuthReissueAccessTokenResponse>> reissueAccessToken(
        @CookieValue(name = "refresh_token") String refreshToken
    ) {
        String newAccessToken = authService.reissueAccessToken(refreshToken);
        return ResponseEntity.ok()
                .body(BaseResponse.of(new AuthReissueAccessTokenResponse(newAccessToken)));
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
