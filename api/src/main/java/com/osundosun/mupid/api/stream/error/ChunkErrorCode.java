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
    CHUNK_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 청크를 찾을 수 없습니다."),

    /**
     * 청크 순서가 스트림에 선언된 청크 개수를 벗어났습니다.
     */
    INVALID_CHUNK_INDEX(HttpStatus.BAD_REQUEST, "청크 순서가 올바르지 않습니다."),

    /**
     * 업로드할 내용이 없습니다.
     */
    EMPTY_CHUNK_FILE(HttpStatus.BAD_REQUEST, "청크 파일이 비어 있습니다."),

    /**
     * 청크 파일이 너무 큽니다.
     */
    CHUNK_FILE_SIZE_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "청크 파일 용량이 너무 큽니다."),

    /**
     * 커밋하려는데 스토리지에 파일이 없습니다.
     */
    CHUNK_NOT_UPLOADED(HttpStatus.CONFLICT, "스토리지에 청크 파일이 올라와 있지 않습니다."),

    /**
     * 스토리지에 저장된 크기가 신고한 크기와 다릅니다.
     */
    CHUNK_SIZE_MISMATCH(HttpStatus.CONFLICT, "스토리지에 저장된 청크 크기가 일치하지 않습니다.");
    
    private final HttpStatus status;
    private final String message;
    
}
