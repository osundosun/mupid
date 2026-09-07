package com.osundosun.mupid.api.user.enumerate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRole {
    
    Guest(0, "게스트", "GUEST"),
    User(1, "사용자", "USER"),
    Admin(99, "관리자", "ADMIN");

    private final Integer level;
    private final String displayName;
    private final String code;

    private static final Map<String, UserRole> CODE_MAP = Collections.unmodifiableMap(
        Arrays.stream(values())
            .collect(Collectors.toMap(it -> it.getCode(), Function.identity()))
    );

    public static UserRole fromCode(String code) {
        if (code == null) return null;
        UserRole value = CODE_MAP.get(code);
        if (value == null)
            throw new IllegalArgumentException("Unknown code (%s)".formatted(code));
        return value;
    }
    
}
