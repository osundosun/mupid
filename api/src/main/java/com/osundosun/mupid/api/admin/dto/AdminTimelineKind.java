package com.osundosun.mupid.api.admin.dto;

/**
 * 타임라인 축(axis)이 시간축 위에서 **점인지 구간인지**.
 *
 * <p>가사 한 줄은 곡 내의 한 시점(점)이고, 구간 분석은 시작~끝을 가진 범위다. 화면은 이
 * 값으로 "세로 눈금"과 "가로 막대" 중 무엇을 그릴지 정한다. 자원 이름에 박아 두지 않는
 * 이유는 380-A 의 대표 컬럼·자식 링크와 같다 — 자원이 늘어도 화면은 같은 코드로 그린다.
 */
public enum AdminTimelineKind {

    /** 곡 내의 한 시점. {@code timeField} 하나만 쓴다. */
    POINT,

    /** 시작~끝을 가진 범위. {@code startField}·{@code endField} 를 쓴다. */
    RANGE

}
