package com.osundosun.mupid.api.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    
    HttpStatus getStatus();

    String name();

    default String getCode() {
        return "MUPID_%s".formatted(name());
    }
    
    String getMessage();

}
