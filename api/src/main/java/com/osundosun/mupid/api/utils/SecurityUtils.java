package com.osundosun.mupid.api.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.osundosun.mupid.api.user.entity.User;

public final class SecurityUtils {
    
    public static User getAuthUser() {
        Authentication auth = getAuth();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }        
        return (User)auth.getPrincipal();
    }

    private static Authentication getAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

}
