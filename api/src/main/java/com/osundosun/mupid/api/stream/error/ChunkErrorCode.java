package com.osundosun.mupid.api.stream.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ChunkErrorCode implements ErrorCode {
    
    /**
     * 요청한 청크를 찾을 수 없습니다.
     */
    CHUNK_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 청크를 찾을 수 없습니다.");
    
    private final HttpStatus status;
    private final String message;
    
}
