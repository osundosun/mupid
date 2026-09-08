package com.osundosun.mupid.api.common.exception;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
    
}
