package com.osundosun.mupid.api.report.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReportErrorCode implements ErrorCode {

    /**
     * 조회할 수 없는 날짜입니다.
     */
    REPORT_INVALID_DATE(HttpStatus.BAD_REQUEST, "조회할 수 없는 날짜입니다."),

    /**
     * 조회 기간이 너무 깁니다.
     *
     * 한 번에 볼 수 있는 창은 {@code ReportRange.MAX_DAYS}(31일)까지다. 넘으면
     * <b>잘라서 주지 않고 거절한다.</b> 잘라 주면 클라이언트가 "이 기간에는 기록이
     * 없다"로 읽는데 실제로는 잘린 것이라, 틀린 답이 맞는 답처럼 보인다. 자세한
     * 근거는 {@code ReportRange} 의 상수 주석 참고.
     */
    REPORT_RANGE_TOO_LONG(HttpStatus.BAD_REQUEST, "조회 기간은 31일까지입니다."),

    /**
     * 취향 유형 기준 데이터가 없습니다.
     *
     * `mubti` 표 16행은 사람이 넣는다 (`ddl-auto: none`). 온보딩이 `codes` 표에
     * 걸리는 것과 같은 구조라 같은 상태 코드로 답한다.
     */
    REPORT_MUBTI_NOT_SEEDED(HttpStatus.SERVICE_UNAVAILABLE, "취향 유형 기준 데이터가 없습니다."),

    /**
     * 청취 분석 데이터를 읽지 못했습니다.
     */
    REPORT_ANALYTICS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "청취 분석 데이터를 읽지 못했습니다.");

    private final HttpStatus status;
    private final String message;

}
