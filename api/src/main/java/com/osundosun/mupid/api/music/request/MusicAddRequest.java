package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MusicAddRequest(

    @NotNull(message = "앨범 정보가 입력되지 않았습니다.")
    Integer albumId,

    @NotNull(message = "아티스트 정보가 입력되지 않았습니다.")
    Integer artistId,

    @NotBlank(message = "타이틀이 입력되지 않았습니다.")
    String title,

    @NotNull(message = "발매일이 입력되지 않았습니다.")
    LocalDateTime releaseDate

) {
    
}
