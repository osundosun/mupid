package com.osundosun.mupid.api.user.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserStatus {

    PENDING("인증 필요"),
    ACTIVE("활성화"),
    WITHDRAW("삭제된 사용자");

    private final String displayName;

}
