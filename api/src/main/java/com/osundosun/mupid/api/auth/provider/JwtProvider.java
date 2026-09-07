package com.osundosun.mupid.api.auth.provider;

import java.util.Date;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.user.entity.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

@Component
public class JwtProvider {

    public static final long ACCESS_TOKEN_VALIDITY_MS = 1000L * 60 * 60; // 1H
    public static final long REFRESH_TOKEN_VALIDITY_MS = 1000L * 60 * 60 * 24 * 7; // 7D

    private final SecretKey secretKey;

    public JwtProvider(
        @Value("${jwt.secret}") String jwtSecret
    ) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    public String issueAccessToken(User user) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + ACCESS_TOKEN_VALIDITY_MS);

        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("role", user.getRole().getCode())
            .claim("token_type", "ACCESS")
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact();
    }

    public String issueRefreshToken(User user) {
        Date now = new Date();
        Date validity = new Date(now.getTime() + REFRESH_TOKEN_VALIDITY_MS);

        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("token_type", "REFRESH")
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact();
    }
    
}
