package com.osundosun.mupid.api.report.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class MubtiClass {

    /**
     * 청취 포인트
     */
    @Getter
    @AllArgsConstructor
    public static enum A {
        
        V("보컬파"),
        I("멜로디파");

        private final String displayName;

    }
    
    /**
     * 선곡 성향
     */
    @Getter
    @AllArgsConstructor
    public static enum B {
        
        T("트렌드형"),
        M("매니아형");

        private final String displayName;

    }

    /**
     * 재생 방식
     */
    @Getter
    @AllArgsConstructor
    public static enum C {
        
        F("완주형"),
        S("스킵형");

        private final String displayName;

    }

    /**
     * 탐색 성향
     */
    @Getter
    @AllArgsConstructor
    public static enum D {
        
        C("국밥형"),
        E("개척형");

        private final String displayName;

    }

}
