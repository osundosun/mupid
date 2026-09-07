package com.osundosun.mupid.api.common.error;

import org.springframework.http.HttpStatus;

public interface ErrorCode {
    
    HttpStatus getStatus();

    String getCode();
    
    String getMessage();

}
