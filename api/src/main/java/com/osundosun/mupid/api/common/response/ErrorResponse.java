package com.osundosun.mupid.api.common.response;

import com.osundosun.mupid.api.common.error.ErrorCode;

public record ErrorResponse(

    String code,

    String message

) {
    
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }

}
