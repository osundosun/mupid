package com.osundosun.mupid.api.vector.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 벡터 등록 API 의 오류 (S15P21A204-457, 설계 §6.6).
 *
 * <p><b>4xx 는 배치가 다시 보내도 같은 답이다</b> — 본문을 고치거나(형식·차원), 구간·분석 버전을
 * 먼저 맞춰야 한다(409). <b>5xx·429 는 같은 본문을 다시 보내면 된다</b> — Spring 은 벡터를 따로
 * 보관하지 않고, 원본은 배치가 가진다. {@code music_preprocessing} 의 클라이언트가 이 구분으로
 * 재시도를 고른다.
 */
@Getter
@RequiredArgsConstructor
public enum VectorErrorCode implements ErrorCode {

    // ── 본문 형식 ────────────────────────────────────────────────

    VECTOR_ENCODING_INVALID(HttpStatus.BAD_REQUEST, "벡터는 float32 리틀엔디언 배열의 base64 여야 합니다."),

    VECTOR_DIMENSION_MISMATCH(HttpStatus.BAD_REQUEST, "벡터 차원이 컬렉션 정의와 다릅니다."),

    VECTOR_NOT_FINITE(HttpStatus.BAD_REQUEST, "벡터에 NaN 이나 무한대가 있습니다."),

    VECTOR_NOT_NORMALIZED(HttpStatus.BAD_REQUEST, "mert 는 중심화한 단위 벡터(u)여야 합니다. 원본 Z 를 보낸 것 같습니다."),

    VECTOR_NORM_INVALID(HttpStatus.BAD_REQUEST, "r(‖Z-μ‖)은 0보다 큰 유한한 값이어야 합니다."),

    VECTOR_MODEL_VERSION_INVALID(HttpStatus.BAD_REQUEST, "모델 버전 형식이 올바르지 않습니다. (예: v3)"),

    VECTOR_MODEL_REQUIRED(HttpStatus.BAD_REQUEST, "구간마다 같은 모델 버전의 stage1 벡터가 하나 이상 있어야 합니다."),

    VECTOR_BATCH_TOO_LARGE(HttpStatus.BAD_REQUEST, "한 번에 보낼 수 있는 구간 수를 넘었습니다."),

    VECTOR_SECTION_DUPLICATED(HttpStatus.BAD_REQUEST, "같은 구간이 두 번 들어 있습니다."),

    // ── MySQL 과의 대조 ──────────────────────────────────────────

    EMBEDDING_VERSION_MISMATCH(HttpStatus.CONFLICT, "임베딩 버전이 컬렉션과 다릅니다."),

    VECTOR_SECTIONS_MISMATCH(HttpStatus.CONFLICT, "구간 수·순서가 등록된 구간과 다릅니다. 구간을 먼저 등록하세요."),

    VECTOR_ANALYSIS_VERSION_MISMATCH(HttpStatus.CONFLICT, "분석 버전이 등록된 구간의 현재 버전과 다릅니다."),

    VECTOR_MUSIC_HAS_NO_SECTIONS(HttpStatus.CONFLICT, "구간이 등록되지 않은 곡입니다. 구간을 먼저 등록하세요."),

    VECTOR_SECTION_UNKNOWN(HttpStatus.NOT_FOUND, "등록되지 않은 구간이 있습니다."),

    VECTOR_POINT_MISSING(HttpStatus.CONFLICT, "Qdrant 에 없는 구간이 있습니다. 곡 전체 벡터를 먼저 등록하세요."),

    // ── 저장소 ──────────────────────────────────────────────────

    VECTOR_STORE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "벡터 저장소가 설정되지 않았습니다."),

    VECTOR_COLLECTION_MISSING(HttpStatus.SERVICE_UNAVAILABLE, "벡터 컬렉션이 없습니다. 운영 절차로 먼저 만드세요."),

    VECTOR_STORE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "벡터 저장소에 쓰지 못했습니다. 같은 요청을 다시 보내세요."),

    VECTOR_BUSY(HttpStatus.TOO_MANY_REQUESTS, "벡터 쓰기가 밀려 있습니다. 잠시 뒤 다시 보내세요.");

    private final HttpStatus status;
    private final String message;

}
