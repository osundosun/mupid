package com.osundosun.mupid.api.system.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * ① 업로드 요청 — 자리를 잡는다. 바이트는 이 요청에 없다.
 *
 * <p>형식 규칙(이름·버전·파일명·확장자·sha256·크기 상한)은 서비스가 본다. 여기서는 "비었나"
 * 만 본다 — 규칙마다 다른 오류 코드를 돌려주려면 한 곳에서 검사하는 편이 낫다.
 */
public record SystemObjectCreateRequest(

    /**
     * 종류. 지금은 {@code MODEL} 만 받는다
     */
    @NotBlank(message = "종류가 입력되지 않았습니다.")
    String kind,

    /**
     * 모델 이름 (예: stage1)
     */
    @NotBlank(message = "이름이 입력되지 않았습니다.")
    String name,

    /**
     * 버전 (예: v3)
     */
    @NotBlank(message = "버전이 입력되지 않았습니다.")
    String version,

    /**
     * 파일명 (예: user_encoder.npz)
     */
    @NotBlank(message = "파일명이 입력되지 않았습니다.")
    String fileName,

    /**
     * 올릴 바이트 수. 업로드의 Content-Length 와 완료 때 스토리지 크기가 이것과 같아야 한다
     */
    @NotNull(message = "크기가 입력되지 않았습니다.")
    @Positive(message = "크기는 양수여야 합니다.")
    Long sizeBytes,

    /**
     * 올린 쪽이 계산한 sha256 (소문자 hex 64자). 완료 때 서버가 직접 잰 값과 대조한다
     */
    @NotBlank(message = "sha256 이 입력되지 않았습니다.")
    String sha256,

    /**
     * 이 버전에 들어가는 파일명 전부 — {@code models.lock} 의 {@code files} 키.
     *
     * <p>파일마다 같은 목록을 싣는다. 첫 요청이 버전의 목록을 정하고, 서버는 그 목록이
     * 모두 완료되면 버전을 후보로 올린다.
     */
    @NotEmpty(message = "버전 파일 목록이 입력되지 않았습니다.")
    @Size(max = 32, message = "버전 파일은 32개까지입니다.")
    List<String> versionFiles

) {

}
