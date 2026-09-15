package com.osundosun.mupid.api.report.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class MubtiClass {

    /**
     * 네 축이 공유하는 모양. 판정 결과를 축마다 다른 타입으로 다루면 응답을 만들 때
     * 네 갈래 분기가 생긴다. 글자(`name()`)와 한글 이름만 있으면 되므로 인터페이스로
     * 묶는다.
     *
     * Java enum 은 상속이 안 되지만 인터페이스는 구현할 수 있다 — `ErrorCode` 를
     * 인터페이스로 둔 것과 같은 이유다.
     */
    public interface Axis {

        /** `V` `I` `T` `M` `F` `S` `C` `E` */
        String name();

        String getDisplayName();

    }

    /**
     * 청취 포인트
     */
    @Getter
    @AllArgsConstructor
    public static enum A implements Axis {

        V("보컬파"),
        I("멜로디파");

        private final String displayName;

        /** 반대편 글자. 화면이 "V ↔ I" 막대를 그리는 데 쓴다. */
        public A opposite() {
            return this == V ? I : V;
        }

    }

    /**
     * 선곡 성향
     */
    @Getter
    @AllArgsConstructor
    public static enum B implements Axis {

        T("트렌드형"),
        M("매니아형");

        private final String displayName;

        public B opposite() {
            return this == T ? M : T;
        }

    }

    /**
     * 재생 방식
     */
    @Getter
    @AllArgsConstructor
    public static enum C implements Axis {

        F("완주형"),
        S("스킵형");

        private final String displayName;

        public C opposite() {
            return this == F ? S : F;
        }

    }

    /**
     * 탐색 성향
     */
    @Getter
    @AllArgsConstructor
    public static enum D implements Axis {

        C("국밥형"),
        E("개척형");

        private final String displayName;

        public D opposite() {
            return this == C ? E : C;
        }

    }

}
