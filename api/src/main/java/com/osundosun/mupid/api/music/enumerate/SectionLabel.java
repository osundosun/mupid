package com.osundosun.mupid.api.music.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SectionLabel {

    INTRO("인트로"),
    VERSE("벌스"),
    PRE_CHORUS("프리 코러스"),
    CHORUS("코러스"),
    BRIDGE("브릿지"),
    BREAK("브레이크"),
    INST("인스트"),
    SOLO("솔로"),
    OUTRO("아웃트로");

    private final String displayName;
    
}
