package com.osundosun.mupid.api.common.exception;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.error.ErrorCode;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.response.ErrorResponse;

import io.jsonwebtoken.JwtException;

import lombok.extern.slf4j.Slf4j;

@Slf4j 
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    /**
     * 오류 코드를 헤더로도 내보낸다.
     *
     * nginx 의 auth_request 는 서브요청의 401/403 만 클라이언트로 넘기고 나머지는
     * 500 으로 접어 버려서 본문이 사라진다. 헤더에 실어 두면
     * `auth_request_set` + `add_header` 로 사유를 살려 보낼 수 있다.
     */
    public static final String ERROR_CODE_HEADER = "X-Mupid-Error";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse<Void>> businessError(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .header(ERROR_CODE_HEADER, errorCode.getCode())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse<Void>> notFound(NoResourceFoundException e) {
        // Endpoint가 존재하지 않습니다.
        return businessError(new BusinessException(GlobalErrorCode.NOT_FOUND));
    }

    /**
     * 경로는 맞는데 요청 방식이 다를 때. <b>405</b> 다.
     *
     * <h2>왜 필요한가</h2>
     *
     * 이 핸들러가 없으면 {@code HttpRequestMethodNotSupportedException} 이 맨 아래
     * {@code Exception} 핸들러에 먹혀 <b>500</b> 으로 나간다. 보낸 쪽은 "서버가 고장 났다"로
     * 읽고 5xx 를 재시도 대상으로 잡아 쓸데없이 다시 보낸다 — 다시 보내도 같은 답인데도.
     * 로그도 ERROR 와 스택트레이스로 오염된다.
     *
     * <p>같은 이유로 {@code AccessDeniedException}(403)·{@code JwtException}(401)·
     * {@code MethodArgumentTypeMismatchException}(400) 에도 핸들러를 붙여 왔다.
     * <b>클라이언트 잘못을 5xx 로 내보내면 서버 장애로 읽힌다</b>는 같은 문제다.
     *
     * <p>{@code NoResourceFoundException}(404) 과 짝이다. 그쪽은 "그런 주소가 없다",
     * 이쪽은 "그 주소가 이 방식은 안 받는다" 다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return businessError(new BusinessException(GlobalErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse<Void>> noRequestBody(HttpMessageNotReadableException e) {
        // 요청에 필요한 데이터가 입력되지 않았습니다.
        return businessError(new BusinessException(GlobalErrorCode.NOT_READABLE_MESSAGE));
    }

    /*
     * MethodArgumentNotValidException(JSON 본문)은 BindException 의 하위 타입이다.
     * 상위로 잡아 두면 @ModelAttribute 로 받는 쿼리 파라미터 검증 실패도 같은
     * 형식으로 나간다. 안 그러면 그쪽만 500 이 된다.
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> invalidArgs(BindException e) {
        // 데이터가 잘못 입력되었습니다.
        Map<String, String> errorBody = new HashMap<>();
        for (FieldError fieldError : e.getFieldErrors()) {
            String fieldName = fieldError.getField();
            String errorMessage = fieldError.getDefaultMessage();
            errorBody.put(fieldName, errorMessage);
        }
        return ResponseEntity
                .status(GlobalErrorCode.INVALID_REQUEST_BODY.getStatus())
                // businessError 와 같은 규약을 지킨다. 오류 코드는 모든 오류 응답의
                // 헤더에도 실린다 (본문이 사라지는 경로 대비).
                .header(ERROR_CODE_HEADER, GlobalErrorCode.INVALID_REQUEST_BODY.getCode())
                .body(ErrorResponse.of(GlobalErrorCode.INVALID_REQUEST_BODY, errorBody));
    }

    /**
     * 쿼리 파라미터·경로 변수의 타입이 안 맞을 때.
     *
     * `?page=abc`, `?type=LOVE`(enum 에 없는 값) 같은 것이다. 그냥 두면 500 으로
     * 나가서 서버가 고장 난 것처럼 보인다. 보낸 쪽 잘못이므로 400 으로 돌린다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> typeMismatch(MethodArgumentTypeMismatchException e) {
        Map<String, String> errorBody = new HashMap<>();
        errorBody.put(e.getName(), describeMismatch(e));
        return ResponseEntity
                .status(GlobalErrorCode.INVALID_REQUEST_BODY.getStatus())
                .header(ERROR_CODE_HEADER, GlobalErrorCode.INVALID_REQUEST_BODY.getCode())
                .body(ErrorResponse.of(GlobalErrorCode.INVALID_REQUEST_BODY, errorBody));
    }

    /** enum 이면 고를 수 있는 값을 알려 준다. 그 편이 고치기 쉽다. */
    private String describeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> required = e.getRequiredType();
        if (required != null && required.isEnum()) {
            return "%s 중 하나여야 합니다.".formatted(
                Arrays.stream(required.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", ")));
        }
        return "값의 형식이 올바르지 않습니다.";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse<Void>> forbidden(AccessDeniedException e) {
        /*
         * @PreAuthorize(@SystemRole·@AdminRole) 가 거부하면 컨트롤러 메서드가 호출되기 직전에
         * AuthorizationDeniedException(= AccessDeniedException) 이 던져진다. 이건 필터가 아니라
         * 핸들러 호출 지점에서 나므로 SecurityConfig 의 AuthForbiddenHandler 를 타지 않고
         * 아래 Exception 핸들러에 먹혀 500 이 나갔다. 여기서 403 으로 되돌린다.
         */
        return businessError(new BusinessException(AuthErrorCode.FORBIDDEN));
    }

    /**
     * 토큰이 못 쓸 물건이면 **401** 이다.
     *
     * 재발급(`GET /api/v1/auth`)은 필터가 아니라 컨트롤러에서 토큰을 푼다. 그래서
     * 모양이 깨졌거나(`MalformedJwtException`) 서명이 안 맞거나 만료된 토큰,
     * 탈퇴·삭제된 사람을 가리키는 토큰이 그대로 아래 Exception 핸들러에 먹혀
     * **500** 으로 나갔다. 브라우저에 남아 있던 낡은 쿠키 하나면 첫 화면부터
     * 서버가 고장 난 것처럼 보인다. 401 이어야 프런트가 로그인으로 보낸다.
     */
    @ExceptionHandler({ JwtException.class, BadCredentialsException.class })
    public ResponseEntity<ErrorResponse<Void>> unauthorized(Exception e) {
        return businessError(new BusinessException(AuthErrorCode.UNAUTHORIZED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> serverError(Exception e) {
        // 서버 내부 오류가 발생했습니다.
        log.error(e.getMessage());
        e.printStackTrace();
        return businessError(new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR));
    }

}
