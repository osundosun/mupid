package com.osundosun.mupid.api.system.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배치용 Object Storage API 와 모델 버전의 오류.
 *
 * <p>업로드(② {@code PUT /upload/system/…})에서 나는 것은 Nginx {@code auth_request} 가 401·403
 * 밖의 상태를 500 으로 접는다. 그때는 {@code X-Mupid-Error} 헤더의 코드로 읽는다
 * (결정 기록 0016).
 */
@Getter
@RequiredArgsConstructor
public enum SystemObjectErrorCode implements ErrorCode {

    // ── ① 요청 검사 ──────────────────────────────────────────────

    UNSUPPORTED_OBJECT_KIND(HttpStatus.BAD_REQUEST, "허용하지 않는 오브젝트 종류입니다."),

    INVALID_MODEL_NAME(HttpStatus.BAD_REQUEST, "모델 이름 형식이 올바르지 않습니다."),

    INVALID_MODEL_VERSION(HttpStatus.BAD_REQUEST, "모델 버전 형식이 올바르지 않습니다. (예: v3)"),

    INVALID_OBJECT_FILE_NAME(HttpStatus.BAD_REQUEST, "파일명 형식이 올바르지 않거나 허용하지 않는 확장자입니다."),

    INVALID_SHA256(HttpStatus.BAD_REQUEST, "sha256 은 소문자 hex 64자여야 합니다."),

    OBJECT_FILE_SIZE_EXCEEDED(HttpStatus.CONTENT_TOO_LARGE, "파일 용량이 너무 큽니다."),

    MANIFEST_REQUIRED(HttpStatus.BAD_REQUEST, "버전 파일 목록에 manifest.json 이 있어야 합니다."),

    OBJECT_FILE_NOT_DECLARED(HttpStatus.BAD_REQUEST, "올리려는 파일이 버전 파일 목록에 없습니다."),

    // ── 불변 ─────────────────────────────────────────────────────

    MODEL_VERSION_FILES_MISMATCH(HttpStatus.CONFLICT, "이 버전에 이미 기록된 파일 목록과 다릅니다."),

    OBJECT_IMMUTABLE(HttpStatus.CONFLICT, "같은 이름·버전·파일명으로 다른 내용을 올릴 수 없습니다."),

    // ── ② 업로드·③ 완료 ─────────────────────────────────────────

    SYSTEM_OBJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 오브젝트를 찾을 수 없습니다."),

    OBJECT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "완료된 오브젝트는 다시 올릴 수 없습니다."),

    UPLOAD_LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, "업로드에는 Content-Length 가 있어야 합니다."),

    UPLOAD_SIZE_MISMATCH(HttpStatus.CONFLICT, "업로드 크기가 요청에서 신고한 크기와 다릅니다."),

    OBJECT_NOT_UPLOADED(HttpStatus.CONFLICT, "스토리지에 파일이 올라와 있지 않습니다."),

    OBJECT_SIZE_MISMATCH(HttpStatus.CONFLICT, "스토리지에 저장된 크기가 신고한 크기와 다릅니다."),

    OBJECT_SHA256_MISMATCH(HttpStatus.CONFLICT, "스토리지에 저장된 파일의 sha256 이 신고한 값과 다릅니다."),

    // ── 받기 ─────────────────────────────────────────────────────

    OBJECT_NOT_COMPLETED(HttpStatus.CONFLICT, "아직 완료되지 않은 오브젝트입니다."),

    // ── 모델 버전 ────────────────────────────────────────────────

    MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 모델을 찾을 수 없습니다."),

    MODEL_VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 모델 버전을 찾을 수 없습니다."),

    MODEL_VERSION_NOT_READY(HttpStatus.CONFLICT, "파일이 모두 올라오지 않은 버전은 채택할 수 없습니다."),

    MODEL_VERSION_RETIRED(HttpStatus.CONFLICT, "폐기된 버전은 채택할 수 없습니다."),

    MODEL_VERSION_IN_SERVICE(HttpStatus.CONFLICT, "지금 쓰는(가장 늦게 채택된) 버전은 폐기할 수 없습니다. 다음 버전을 먼저 채택하세요.");

    private final HttpStatus status;
    private final String message;

}
