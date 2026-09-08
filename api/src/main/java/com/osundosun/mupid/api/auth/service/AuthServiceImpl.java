package com.osundosun.mupid.api.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.auth.dto.AuthLoginResult;
import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.auth.provider.AuthProvider;
import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final AuthProvider authProvider;
    private final CacheService cacheService;
    private final UserService userService;

    @Override
    public AuthLoginResult login(String email, String password) {
        User user = userService
                        .getUserByEmail(email)
                        .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(AuthErrorCode.WRONG_EMAIL_OR_PASSWORD);
        }
        return new AuthLoginResult(
            jwtProvider.issueAccessToken(user),
            jwtProvider.issueRefreshToken(user),
            user
        );
    }

    @Override
    public String reissueAccessToken(String refreshToken) {
        if (cacheService.exists(refreshToken, CacheKind.BlockToken)) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }
        return authProvider.refresh(refreshToken);
    }

    @Override
    public void logout(List<String> tokens) {
        Instant now = Instant.now();
        for (String token : tokens) {
            Instant expiration = authProvider.parseExpiration(token).toInstant();
            cacheService.set(token, CacheKind.BlockToken, Duration.between(now, expiration));
        }
    }
    
}
