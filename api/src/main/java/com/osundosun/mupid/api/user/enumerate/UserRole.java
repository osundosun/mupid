package com.osundosun.mupid.api.user.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {
    
    ROLE_GUEST(0, "게스트"),
    ROLE_USER(1, "사용자"),
    ROLE_SYSTEM(10, "배치 프로그램"),
    ROLE_ADMIN(99, "관리자");

    private final Integer level;
    private final String displayName;
    
}
