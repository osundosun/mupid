package com.osundosun.mupid.api.admin.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdminErrorCode implements ErrorCode {

    /**
     * 관리 대상 자원을 찾을 수 없습니다.
     */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "관리 대상 자원을 찾을 수 없습니다."),

    /**
     * 요청한 레코드를 찾을 수 없습니다.
     */
    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 레코드를 찾을 수 없습니다."),

    /**
     * 알 수 없는 필드입니다.
     */
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "알 수 없는 필드입니다."),

    /**
     * 필드 값의 형식이 올바르지 않습니다.
     */
    INVALID_FIELD_VALUE(HttpStatus.BAD_REQUEST, "필드 값의 형식이 올바르지 않습니다."),

    /**
     * 식별자 형식이 올바르지 않습니다.
     */
    INVALID_RECORD_ID(HttpStatus.BAD_REQUEST, "식별자 형식이 올바르지 않습니다."),

    /**
     * 레코드를 저장하지 못했습니다.
     */
    RECORD_WRITE_FAILED(HttpStatus.BAD_REQUEST, "레코드를 저장하지 못했습니다."),

    /**
     * 자원을 조회하지 못했습니다. 엔티티 매핑과 실제 테이블이 어긋났을 수 있습니다.
     */
    RESOURCE_QUERY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR,
        "자원을 조회하지 못했습니다. 엔티티 매핑과 실제 테이블이 어긋났을 수 있습니다."),

    /**
     * 자기 자신의 계정은 이 화면에서 바꿀 수 없습니다.
     */
    SELF_MODIFICATION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "자기 자신의 계정은 이 화면에서 바꿀 수 없습니다."),

    /**
     * 마지막 관리자 계정은 지우거나 권한을 낮출 수 없습니다.
     */
    LAST_ADMIN_PROTECTED(HttpStatus.BAD_REQUEST, "마지막 관리자 계정은 지우거나 권한을 낮출 수 없습니다."),

    /**
     * ClickHouse 콘솔 주소가 설정되어 있지 않습니다.
     */
    CONSOLE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE,
        "ClickHouse 콘솔 주소가 설정되어 있지 않습니다."),

    /**
     * 조회 기간이 올바르지 않습니다.
     *
     * <p>조용히 당겨 넣지 않고 거절하는 쪽을 골랐다. 1년을 요청했는데 석 달치를 돌려주면
     * <b>화면은 받은 것을 요청한 기간으로 읽는다</b> — "이 기간엔 기록이 없다" 로 보이는데
     * 실제로는 잘린 것이다. {@code MUPID_REPORT_RANGE_TOO_LONG} 과 같은 판단이다.
     */
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "조회 기간이 올바르지 않습니다."),

    /**
     * 분석 데이터를 읽지 못했습니다.
     *
     * <p>ClickHouse 가 죽어 있는 것과 서버가 망가진 것은 다른 사건이다. 마지막 폴백에
     * 먹혀 "서버 내부 오류" 가 되면 보는 사람이 무엇을 확인해야 하는지 알 수 없다 —
     * 관리자 화면의 <b>연결 상태</b> 카드가 그 답을 이미 갖고 있다.
     */
    ANALYTICS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "분석 데이터를 읽지 못했습니다.");

    private final HttpStatus status;
    private final String message;

}
