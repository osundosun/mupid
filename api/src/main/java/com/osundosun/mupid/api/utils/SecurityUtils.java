package com.osundosun.mupid.api.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.osundosun.mupid.api.user.entity.User;

public final class SecurityUtils {
    
    /**
     * 지금 요청을 보낸 사람. <b>로그인하지 않았으면 null 이다.</b>
     *
     * <p>인증이 없을 때 스프링 시큐리티는 컨텍스트를 비워 두지 않고 <b>익명 인증</b>을
     * 넣는다 — principal 이 {@code "anonymousUser"} 라는 문자열이다. 그래서
     * {@code isAuthenticated()} 만 보고 캐스팅하면 익명 요청에서
     * {@code ClassCastException} 이 나고, 그것이 최종 폴백 핸들러에 먹혀 <b>401 이어야
     * 할 응답이 500</b> 으로 나간다. 비로그인으로도 열리는 경로(예: 공개 이미지 조회)가
     * 생기면서 실제로 밟았다.
     *
     * <p>그래서 "인증이 있는가"가 아니라 <b>principal 이 우리 {@link User} 인가</b>를 본다.
     */
    public static User getAuthUser() {
        Authentication auth = getAuth();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }        
        return auth.getPrincipal() instanceof User user ? user : null;
    }

    private static Authentication getAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

}
