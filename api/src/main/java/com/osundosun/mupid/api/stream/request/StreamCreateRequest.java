package com.osundosun.mupid.api.stream.request;

import com.osundosun.mupid.api.stream.enumerate.Codec;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StreamCreateRequest(

    /**
     * 비트레이트 (kbps)
     */
    @NotNull(message = "비트레이트가 입력되지 않았습니다.")
    @Positive(message = "비트레이트는 양수여야 합니다.")
    Integer bitrateKbps,

    /**
     * 코덱 종류 (AAC, OPUS)
     */
    @NotNull(message = "코덱이 입력되지 않았습니다.")
    Codec codec,

    /**
     * 전체 재생 길이 (ms)
     */
    @NotNull(message = "재생 길이가 입력되지 않았습니다.")
    @Positive(message = "재생 길이는 양수여야 합니다.")
    Integer durationMs,

    /**
     * 올릴 청크 개수. 완료 처리 시 실제 업로드 수와 대조한다.
     */
    @NotNull(message = "청크 개수가 입력되지 않았습니다.")
    @Positive(message = "청크 개수는 양수여야 합니다.")
    Integer chunkCount

) {
    
}
