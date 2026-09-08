package com.osundosun.mupid.api.user.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {
    
    GUEST(0, "게스트"),
    USER(1, "사용자"),
    ADMIN(99, "관리자");

    private final Integer level;
    private final String displayName;
    
}
