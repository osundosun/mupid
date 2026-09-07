package com.osundosun.mupid.api.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException e) {
        return handleBusinessException(new BusinessException(GlobalErrorCode.NotFoundError));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        return handleBusinessException(new BusinessException(GlobalErrorCode.NoRequestBody));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error(e.getMessage());
        e.printStackTrace();
        return handleBusinessException(new BusinessException(GlobalErrorCode.InternalServerError));
    }

}
