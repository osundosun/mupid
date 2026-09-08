package com.osundosun.mupid.api.common.response;

import com.osundosun.mupid.api.common.error.ErrorCode;

public record ErrorResponse<T>(

    String code,

    String message,

    T errorDetails

) {
    
    public static <T> ErrorResponse<Void> of(ErrorCode errorCode) {
        return new ErrorResponse<Void>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ErrorResponse<T> of(ErrorCode errorCode, T errorDetails) {
        return new ErrorResponse<T>(errorCode.getCode(), errorCode.getMessage(), errorDetails);
    }

}
