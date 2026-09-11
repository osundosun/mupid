package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AlbumCreateRequest(

    @NotBlank(message = "아티스트가 입력되지 않았습니다.")
    Integer artistId,

    @NotBlank(message = "앨범명이 입력되지 않았습니다.")
    String title,

    @NotNull(message = "발매일이 지정되지 않았습니다.")
    LocalDateTime releaseDate

) {
    
}
