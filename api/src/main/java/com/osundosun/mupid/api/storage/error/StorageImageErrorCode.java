package com.osundosun.mupid.api.storage.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StorageImageErrorCode implements ErrorCode {
    
    /**
     * 이미지 용량이 너무 큽니다.
     */
    IMAGE_FILE_SIZE_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "이미지 용량이 너무 큽니다."),
    
    /**
     * 포맷이 사진 형식이 아니거나 올바르지 않습니다.
     */
    INVALID_IMAGE_FILE_FORMAT(HttpStatus.BAD_REQUEST, "포맷이 사진 형식이 아니거나 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;
    
}
