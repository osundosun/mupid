package com.osundosun.mupid.api.storage.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StorageErrorCode implements ErrorCode {
    
    /**
     * 요청한 파일을 찾을 수 없습니다.
     */
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 파일을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

}
