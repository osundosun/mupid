package com.osundosun.mupid.api.auth.provider;

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

import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
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
    private final SecretKey secretKey;

    public AuthProvider(
        UserRepository userRepository,
        JwtProvider jwtProvider,
        @Value("${jwt.secret}") String jwtSecret
    ) {
        this.userRepository = userRepository;
        this.jwtProvider = jwtProvider;
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
            throw new BadCredentialsException("Invalid token type.");
        }

        int userId = Integer.parseInt(claims.getSubject());
        UserRole userRole = UserRole.fromCode(claims.get("role", String.class));

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
            new SimpleGrantedAuthority(userRole.getCode())
        );
        User principal = userRepository.findById(userId).orElseThrow();
        return new UsernamePasswordAuthenticationToken(principal, accessToken, authorities);
    }

    public String refresh(String refreshToken) {
        Claims claims = parseClaims(refreshToken);

        String tokenType = claims.get("token_type", String.class);
        if (!tokenType.equals("REFRESH")) {
            throw new BadCredentialsException("Invalid token type.");
        }

        int userId = Integer.parseInt(claims.getSubject());
        User user = userRepository.findById(userId).orElseThrow();
        return jwtProvider.issueAccessToken(user);
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
