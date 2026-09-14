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
    INVALID_IMAGE_FILE_FORMAT(HttpStatus.BAD_REQUEST, "포맷이 사진 형식이 아니거나 올바르지 않습니다."),

    /**
     * 프로필 사진의 저장 키는 본인 유저 번호여야 한다. 경로 변수로 받는 값이라
     * 남의 번호를 넣으면 그 사람 프로필 파일을 덮어쓸 수 있다.
     */
    PROFILE_KEY_MISMATCH(HttpStatus.FORBIDDEN, "프로필 사진은 본인 번호를 키로 써야 합니다.");

    private final HttpStatus status;
    private final String message;
    
}
