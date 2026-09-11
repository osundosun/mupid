package com.osundosun.mupid.api.user.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {
    
    ROLE_USER(1, "사용자"),
    ROLE_SYSTEM(50, "시스템"),
    ROLE_ADMIN(99, "관리자");

    private final Integer level;
    private final String displayName;
    
}
