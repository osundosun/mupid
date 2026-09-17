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
     * 이미지가 아닌 파일입니다.
     *
     * <p>이미지 API 로 음원 청크 같은 다른 종류({@code FileType.MUSIC})를 달라고 했을 때다.
     * 이 검사가 없으면 청크 바이트가 표지 자리로 새어 나간다.
     */
    INVALID_FILE_TYPE(HttpStatus.BAD_REQUEST, "이미지 파일이 아닙니다."),

    /**
     * 저장 키가 그 카테고리에 맞지 않습니다.
     *
     * <p>{@code ImageAccessPolicy.OWNER} 카테고리(프로필)에서 키의 마지막 마디가 본인 유저
     * 번호여야 한다. 키가 경로 변수라 그냥 두면 남의 사진 파일을 덮어쓸 수 있다.
     *
     * <p>한때 이름이 {@code PROFILE_KEY_MISMATCH} 였다. 정책을 카테고리 공통으로 올리면서
     * 이 이름이 됐다 — 응답 코드는 {@code MUPID_STORAGE_KEY_MISMATCH} 다.
     */
    STORAGE_KEY_MISMATCH(HttpStatus.FORBIDDEN, "본인 것만 지정할 수 있습니다."),

    /**
     * 그 이미지를 쓸 권한이 없습니다.
     *
     * <p>앨범·아티스트·곡 커버·MuBTI 캐릭터의 업로드·삭제는 배치({@code ROLE_SYSTEM})
     * 이상만 한다. 로그인만 하면 누구나 남의 표지를 덮어쓸 수 있던 것을 막는다.
     */
    STORAGE_WRITE_FORBIDDEN(HttpStatus.FORBIDDEN, "이미지를 등록하거나 삭제할 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;
    
}
