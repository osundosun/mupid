package com.osundosun.mupid.api.auth.service;

import java.time.Duration;
import java.util.Date;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 한 명의 토큰을 한꺼번에 무효로 한다 (S15P21A204-454).
 *
 * <p>토큰은 JWT 라 서버에 목록이 없다. 그래서 토큰을 하나씩 막지 않고 "이 시각 이전에
 * 발급된 것은 무효" 라는 경계 하나를 사용자별로 적는다({@link CacheKind#TokenRevokedBefore}).
 * 폐기 뒤 다시 발급받은 토큰은 경계보다 늦게 발급되어 통과한다.
 */
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    /** 가장 긴 리프레시 토큰보다 하루 길게 둔다. 그 뒤엔 경계보다 먼저 발급된 토큰이 살아 있을 수 없다 */
    private static final Duration MARK_TTL =
        Duration.ofDays(JwtProvider.MAX_SYSTEM_REFRESH_TOKEN_VALIDITY_DAYS + 1L);

    private final CacheService cacheService;

    public void revokeAll(int userId) {
        cacheService.set(
            String.valueOf(userId),
            String.valueOf(System.currentTimeMillis()),
            CacheKind.TokenRevokedBefore,
            MARK_TTL
        );
    }

    /**
     * 경계 이전에 발급된 토큰인가.
     *
     * <p>JWT 의 {@code iat} 는 초 단위라 잘린다. 폐기와 같은 초에 발급된 토큰은 폐기 전이었을 수도
     * 있으므로 무효로 본다({@code <=}) — 폐기 직후 같은 초에 새로 받은 토큰도 함께 막히지만, 한 번
     * 더 받으면 된다. 반대로 열어 두면 폐기하려던 토큰이 살아남는다.
     */
    public boolean isRevoked(int userId, Date issuedAt) {
        String mark = cacheService.get(String.valueOf(userId), CacheKind.TokenRevokedBefore);
        if (mark == null) {
            return false;
        }
        return issuedAt == null || issuedAt.getTime() <= Long.parseLong(mark);
    }

}
