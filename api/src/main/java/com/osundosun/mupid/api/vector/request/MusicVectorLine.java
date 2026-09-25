package com.osundosun.mupid.api.vector.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 구간 하나의 벡터. 값은 모두 float32 리틀엔디언 배열의 base64 다({@link MusicVectorsRequest}).
 *
 * @param externalId 구간 문자열 ID({@code T000001#3}). 포인트 id 는 이것의 UUIDv5 다
 * @param r          {@code ‖Z-μ‖}. 원본 복원 {@code Z = μ + r·u} 에 쓴다(동결 μ, 설계 §6.3)
 * @param mert       중심화 방향 {@code u = (Z-μ)/‖Z-μ‖} (1536). 단위 벡터가 아니면 거절한다
 * @param featV1     랭킹 입력 특징 {@code X} (285)
 * @param stage1     모델 버전 → 구간 벡터 (128). 예 {@code {"v1": "…"}} — 재계산 중이라 두 버전을 함께
 *                   보낼 수 있다. 포인트를 통째로 갈아 끼우므로 <b>빠진 버전은 지워진다</b>
 * @param clap       CLAP 오디오 임베딩 (512). 있는 구간만 싣고, 없으면 비운다
 */
public record MusicVectorLine(
    @NotBlank String externalId,
    @NotNull Double r,
    @NotBlank String mert,
    @NotBlank String featV1,
    @NotEmpty Map<String, String> stage1,
    String clap
) {
}
