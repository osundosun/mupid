package com.osundosun.mupid.api.auth.filter;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.osundosun.mupid.api.auth.exception.AuthUnauthorizedHandler;
import com.osundosun.mupid.api.auth.provider.AuthProvider;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    private final AuthProvider authProvider;
    private final CacheService cacheService;
    private final AuthUnauthorizedHandler authUnauthorizedHandler;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String accessToken = resolveAccessToken(request);

            if (accessToken == null ||
                !authProvider.isValidToken(accessToken) ||
                cacheService.exists(accessToken, CacheKind.BlockToken)
            ) {
                filterChain.doFilter(request, response);
                return;
            }

            SecurityContext context = SecurityContextHolder.getContext();
            context.setAuthentication(authProvider.auth(accessToken));
            filterChain.doFilter(request, response);
        } catch (AuthenticationException e) {
            authUnauthorizedHandler.commence(request, response, e);
            return;
        }
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
    
}
