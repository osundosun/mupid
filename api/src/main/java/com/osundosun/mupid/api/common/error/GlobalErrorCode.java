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
    /**
     * 요청 본문을 읽을 수 없습니다. **400** 이다.
     *
     * <p><b>404 였다 (2026-09-18 정정).</b> 잘못된 JSON 을 보내면 "요청 데이터
     * 파싱에 실패했습니다" 가 404 로 나갔다 — 보낸 쪽은 그 주소가 없다고 읽고
     * 경로를 고치려 든다. 실은 본문이 잘못된 것이라 400 이 맞다.
     *
     * <p>이 오류만 404 인 이유는 코드·주석·커밋 어디에도 없었다. [decision/0003]
     * (../decision/0003-error-code-enum-owns-http-status.md) 이 "enum 에 잘못 박힌
     * 상태가 조용히 계약이 된다" 며 이 항목을 예로 들었고, Bruno 문서에도 404 로
     * 기록돼 있었다 — 그 문서도 함께 고쳤다.
     */
    NOT_READABLE_MESSAGE(HttpStatus.BAD_REQUEST, "요청 데이터 파싱에 실패했습니다."),

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
