package com.osundosun.mupid.api.stream.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 청크 바이트가 스토리지에 올라간 뒤, 메타데이터를 확정하는 요청.
 *
 * 파일 자체는 nginx 가 스토리지로 직행시키므로 스프링은 바이트를 보지 않는다.
 * 그래서 길이·해시는 올린 쪽이 알려주고, 서버는 오브젝트의 존재와 크기만
 * HEAD 로 대조한다.
 */
public record ChunkCommitRequest(

    /**
     * 이 청크의 재생 길이 (ms)
     */
    @NotNull(message = "청크 길이가 입력되지 않았습니다.")
    @Positive(message = "청크 길이는 양수여야 합니다.")
    Integer durationMs,

    /**
     * 올린 쪽이 계산한 SHA-256 (소문자 hex)
     */
    @NotBlank(message = "청크 해시가 입력되지 않았습니다.")
    String fileHash,

    /**
     * 올린 바이트 수. 스토리지에 저장된 크기와 다르면 커밋을 거부한다.
     */
    @NotNull(message = "청크 크기가 입력되지 않았습니다.")
    @Positive(message = "청크 크기는 양수여야 합니다.")
    Integer sizeBytes

) {
    
}
