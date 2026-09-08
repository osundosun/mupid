package com.osundosun.mupid.api.common.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.osundosun.mupid.api.common.error.ErrorCode;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j 
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse<Void>> businessError(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse<Void>> serverError(Exception e) {
        // 서버 내부 오류가 발생했습니다.
        log.error(e.getMessage());
        e.printStackTrace();
        return businessError(new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR));
    }

}
