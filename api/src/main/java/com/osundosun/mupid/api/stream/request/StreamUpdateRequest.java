package com.osundosun.mupid.api.stream.request;

import com.osundosun.mupid.api.stream.enumerate.StreamStatus;

import jakarta.validation.constraints.NotNull;

public record StreamUpdateRequest(

    /**
     * 바꿀 인코딩 상태. DONE 으로 올리려면 청크가 모두 업로드되어 있어야 한다.
     */
    @NotNull(message = "상태가 입력되지 않았습니다.")
    StreamStatus status

) {
    
}
