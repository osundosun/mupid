package com.osundosun.mupid.api.user.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserImageErrorCode implements ErrorCode {
    
    /**
     * 프로필 이미지 용량이 너무 큽니다.
     */
    UserImageFileSizeExceeded(HttpStatus.CONTENT_TOO_LARGE, "MUPID_USER_IMAGE_FILE_SIZE_EXCEEDED", "프로필 이미지 용량이 너무 큽니다."),
    
    /**
     * 프로필 사진 포맷이 사진 형식이 아니거나 올바르지 않습니다.
     */
    InvalidUserImageFileFormat(HttpStatus.BAD_REQUEST, "MUPID_INVALID_USER_IMAGE_FILE_FORMAT", "프로필 사진 포맷이 사진 형식이 아니거나 올바르지 않습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
    
}
