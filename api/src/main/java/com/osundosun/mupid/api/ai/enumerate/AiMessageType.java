package com.osundosun.mupid.api.ai.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AiMessageType {
    
    SYSTEM("시스템 프롬프트"),
    USER("사용자"),
    AI("인공지능"),
    ETC("기타");

    private final String displayName;

}
