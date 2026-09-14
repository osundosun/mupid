package com.osundosun.mupid.api.common.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 음악 선곡 주체
 */
@Getter
@AllArgsConstructor
public enum Selector {

    USER("사용자"),
    AI("인공지능");

    private final String displayName;
    
}
