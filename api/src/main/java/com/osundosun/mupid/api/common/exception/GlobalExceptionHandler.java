package com.osundosun.mupid.api.common.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.error.ErrorCode;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.response.ErrorResponse;

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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse<Void>> noRequestBody(HttpMessageNotReadableException e) {
        // 요청에 필요한 데이터가 입력되지 않았습니다.
        return businessError(new BusinessException(GlobalErrorCode.NOT_READABLE_MESSAGE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse<Map<String, String>>> invalidArgs(MethodArgumentNotValidException e) {
        // 데이터가 잘못 입력되었습니다.
        Map<String, String> errorBody = new HashMap<>();
        for (FieldError fieldError : e.getFieldErrors()) {
            String fieldName = fieldError.getField();
            String errorMessage = fieldError.getDefaultMessage();
            errorBody.put(fieldName, errorMessage);
        }
        return ResponseEntity
                .status(GlobalErrorCode.INVALID_REQUEST_BODY.getStatus())
                .body(ErrorResponse.of(GlobalErrorCode.INVALID_REQUEST_BODY, errorBody));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> serverError(Exception e) {
        // 서버 내부 오류가 발생했습니다.
        log.error(e.getMessage());
        e.printStackTrace();
        return businessError(new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR));
    }

}
