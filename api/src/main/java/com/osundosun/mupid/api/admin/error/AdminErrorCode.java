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
        "ClickHouse 콘솔 주소가 설정되어 있지 않습니다.");

    private final HttpStatus status;
    private final String message;

}
