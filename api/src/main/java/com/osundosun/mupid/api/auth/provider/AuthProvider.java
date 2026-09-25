package com.osundosun.mupid.api.auth.provider;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.auth.dto.TokenPair;
import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.auth.service.TokenRevocationService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class AuthProvider {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;
    private final TokenRevocationService tokenRevocationService;
    private final SecretKey secretKey;

    public AuthProvider(
        UserRepository userRepository,
        JwtProvider jwtProvider,
        TokenRevocationService tokenRevocationService,
        @Value("${jwt.secret}") String jwtSecret
    ) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
        this.tokenRevocationService = tokenRevocationService;
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }
    
    public boolean isValidToken(String token) {
        try {
            Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Authentication auth(String accessToken) {
        Claims claims = parseClaims(accessToken);

        String tokenType = claims.get("token_type", String.class);
        if (!tokenType.equals("ACCESS")) {
            throw new BadCredentialsException(AuthErrorCode.UNAUTHORIZED.getMessage());
        }

        int userId = Integer.parseInt(claims.getSubject());
        UserRole userRole = UserRole.valueOf(claims.get("role", String.class));

        /*
         * 일괄 폐기는 ROLE_SYSTEM 만 대상이라 그 액세스 토큰만 본다. 모든 요청에 Redis 왕복을
         * 하나 더 붙이지 않으려는 것이다. 이걸 보지 않으면 폐기 뒤에도 이미 받아 둔 액세스
         * 토큰으로 1시간 동안 가중치를 올리고 받을 수 있다.
         */
        if (userRole == UserRole.ROLE_SYSTEM
            && tokenRevocationService.isRevoked(userId, claims.getIssuedAt())) {
            throw new BadCredentialsException(AuthErrorCode.UNAUTHORIZED.getMessage());
        }

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
            new SimpleGrantedAuthority(userRole.name())
        );
        User principal = userRepository
                            .findByUserIdAndStatusNot(userId, UserStatus.WITHDRAW)
                            .orElseThrow(() -> new BadCredentialsException(UserErrorCode.USER_NOT_FOUND.getMessage()));
        return new UsernamePasswordAuthenticationToken(principal, accessToken, authorities);
    }

    public TokenPair refresh(String refreshToken) {
        Claims claims = parseClaims(refreshToken);

        String tokenType = claims.get("token_type", String.class);
        if (!tokenType.equals("REFRESH")) {
            throw new BadCredentialsException(AuthErrorCode.UNAUTHORIZED.getMessage());
        }

        int userId = Integer.parseInt(claims.getSubject());
        if (tokenRevocationService.isRevoked(userId, claims.getIssuedAt())) {
            throw new BadCredentialsException(AuthErrorCode.UNAUTHORIZED.getMessage());
        }
        User user = userRepository
                            .findByUserIdAndStatusNot(userId, UserStatus.WITHDRAW)
                            .orElseThrow(() -> new BadCredentialsException(UserErrorCode.USER_NOT_FOUND.getMessage()));

        /*
         * ROLE_SYSTEM 은 회전하지 않는다 (S15P21A204-454). 액세스 토큰만 새로 주고 같은 리프레시
         * 토큰을 만료까지 쓴다.
         *
         * CI 는 토큰을 보호된 CI 변수에 두는데, 회전하면 빌드마다 새 토큰을 그 변수에 다시 써야
         * 한다 — CI 에 변수 쓰기 권한(프로젝트 관리 토큰)을 줘야 하고, 쓰기에 실패하면 다음 빌드가
         * 끊긴다. 대신 만료를 길게(최대 180일) 받고, 새면 관리자가 일괄 폐기한다.
         * JupyterHub 배치는 받은 쿠키가 들고 있던 값과 같으면 파일을 다시 쓰지 않는다
         * (`music_preprocessing/api_client.py`).
         */
        if (user.getRole() == UserRole.ROLE_SYSTEM) {
            return new TokenPair(
                jwtProvider.issueAccessToken(user),
                refreshToken,
                claims.getExpiration().toInstant()
            );
        }

        Duration validity = Duration.ofMillis(JwtProvider.REFRESH_TOKEN_VALIDITY_MS);
        return new TokenPair(
            jwtProvider.issueAccessToken(user),
            jwtProvider.issueRefreshToken(user, validity),
            Instant.now().plus(validity)
        );
    }

    public Date parseExpiration(String token) {
        Claims claims = parseClaims(token);
        return claims.getExpiration();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

}
