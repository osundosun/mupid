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
public enum UserStatus {

    Pending("인증 필요", "PENDING"),
    Active("활성화", "ACTIVE"),
    Withdraw("삭제된 사용자", "WITHDRAW");

    private final String displayName;
    private final String code;
    
    private static final Map<String, UserStatus> CODE_MAP = Collections.unmodifiableMap(
        Arrays.stream(values())
            .collect(Collectors.toMap(it -> it.getCode(), Function.identity()))
    );

    public static UserStatus fromCode(String code) {
        if (code == null) return null;
        UserStatus value = CODE_MAP.get(code);
        if (value == null)
            throw new IllegalArgumentException("Unknown code (%s)".formatted(code));
        return value;
    }

}
