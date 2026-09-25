package com.osundosun.mupid.api.auth.provider;

import java.time.Duration;
import java.util.Date;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtProvider {

    public static final long ACCESS_TOKEN_VALIDITY_MS = 1000L * 60 * 60; // 1H
    public static final long REFRESH_TOKEN_VALIDITY_MS = 1000L * 60 * 60 * 24 * 7; // 7D

    /**
     * ROLE_SYSTEM 이 지정할 수 있는 리프레시 토큰 만료의 상한 (6개월, S15P21A204-454).
     *
     * <p>CI 는 토큰을 보호된 CI 변수에 두는데, CI 가 변수를 다시 쓸 권한을 갖지 않게 하려고
     * ROLE_SYSTEM 은 회전하지 않는다({@link AuthProvider#refresh}). 그러면 만료가 곧
     * 사람이 토큰을 갈아 끼우는 주기라 7일은 너무 짧다. 상한을 두는 것은 새어 나간 토큰이
     * 폐기({@code TokenRevocationService}) 없이도 언젠가는 죽게 하려는 것이다.
     */
    public static final int MAX_SYSTEM_REFRESH_TOKEN_VALIDITY_DAYS = 180;

    private final SecretKey secretKey;

    public JwtProvider(
        @Value("${jwt.secret}") String jwtSecret
    ) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    /**
     * 리프레시 토큰의 만료를 정한다. 지정하지 않으면 기본값(7일)이다.
     *
     * <p>지정은 ROLE_SYSTEM 만 할 수 있다 — 사람 계정의 토큰은 브라우저 쿠키에 있고 회전하므로
     * 길게 둘 이유가 없다. 다른 역할이 지정하면 조용히 무시하지 않고 거절한다(무엇을 받았는지
     * 호출한 쪽이 알아야 한다).
     */
    public Duration resolveRefreshTokenValidity(User user, Integer validityDays) {
        if (validityDays == null) {
            return Duration.ofMillis(REFRESH_TOKEN_VALIDITY_MS);
        }
        if (user.getRole() != UserRole.ROLE_SYSTEM) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_VALIDITY_NOT_ALLOWED);
        }
        if (validityDays < 1 || validityDays > MAX_SYSTEM_REFRESH_TOKEN_VALIDITY_DAYS) {
            throw new BusinessException(AuthErrorCode.REFRESH_TOKEN_VALIDITY_OUT_OF_RANGE);
        }
        return Duration.ofDays(validityDays);
    }

    public String issueAccessToken(User user) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + ACCESS_TOKEN_VALIDITY_MS);

        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("role", user.getRole().name())
            .claim("token_type", "ACCESS")
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact();
    }

    public String issueRefreshToken(User user) {
        return issueRefreshToken(user, Duration.ofMillis(REFRESH_TOKEN_VALIDITY_MS));
    }

    public String issueRefreshToken(User user, Duration validityDuration) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + validityDuration.toMillis());

        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("token_type", "REFRESH")
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact();
    }
    
}
