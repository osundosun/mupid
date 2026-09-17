package com.osundosun.mupid.api.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum GlobalErrorCode implements ErrorCode {
    
    /**
     * Endpoint가 존재하지 않습니다.
     */
    NOT_FOUND(HttpStatus.NOT_FOUND, "Endpoint가 존재하지 않습니다."),

    /**
     * 요청 데이터 파싱에 실패했습니다.
     */
    NOT_READABLE_MESSAGE(HttpStatus.NOT_FOUND, "요청 데이터 파싱에 실패했습니다."),

    /**
     * 데이터가 잘못 입력되었습니다.
     */
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "데이터가 잘못 입력되었습니다."),

    /**
     * 서버 내부 오류가 발생했습니다.
     */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    /**
     * 그 주소가 받지 않는 요청 방식입니다.
     *
     * <p>경로는 있는데 메서드가 다를 때다. 그냥 두면 아래 {@code Exception} 핸들러에 먹혀
     * 500 으로 나가고, 보낸 쪽은 서버가 고장 난 것으로 읽는다. 같은 이유로
     * {@code AccessDeniedException}·{@code JwtException}·{@code MethodArgumentTypeMismatchException}
     * 에도 핸들러를 붙였다.
     */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.");

    private final HttpStatus status;
    private final String message;

}
