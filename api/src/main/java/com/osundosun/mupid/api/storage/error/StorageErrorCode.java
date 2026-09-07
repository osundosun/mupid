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
    FileNotFoundError(HttpStatus.NOT_FOUND, "MUPID_FILE_NOT_FOUND", "요청한 파일을 찾을 수 없습니다."),

    /**
     * 파일 키가 중복되었습니다.
     */
    DuplicatedFileKey(HttpStatus.BAD_REQUEST, "MUPID_FILE_KEY_DUPLICATED", "파일 키가 중복되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

}
