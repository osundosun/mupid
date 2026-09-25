package com.osundosun.mupid.api.vector.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 모델 재계산 — 여러 곡의 {@code stage1_v{version}} 만 (S15P21A204-457, 설계 §6.6·§7.3).
 *
 * <p>1,000구간이 상한이다. 구간 벡터 128차 float32 는 512B 라 1,000구간이 바이트로 약 0.5MB
 * (base64 로 약 0.7MB)다 — 설계 기준 90만 구간이면 약 900번이다(§10.3-7).
 */
public record Stage1VectorsRequest(
    @NotEmpty
    @Size(max = MAX_SECTIONS, message = "한 번에 보낼 수 있는 구간은 1,000개입니다.")
    List<@Valid Line> sections
) {

    public static final int MAX_SECTIONS = 1_000;

    /**
     * @param externalId 구간 문자열 ID. Qdrant 에 곡 전체 벡터로 먼저 들어가 있어야 한다
     * @param vector     구간 벡터 (128) — float32 리틀엔디언 배열의 base64
     */
    public record Line(@NotBlank String externalId, @NotBlank String vector) {
    }

}
