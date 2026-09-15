package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LyricAddRequest(

    @NotNull(message = "대상 음악이 지정되지 않았습니다.")
    Integer musicId,

    @NotBlank(message = "가사 내용이 입력되지 않았습니다.")
    String text,

    @NotNull(message = "가사 표시 타이밍이 입력되지 않았습니다.")
    Integer timeMs

) {
    
}
