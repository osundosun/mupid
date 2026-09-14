package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AlbumCreateRequest(

    @NotNull(message = "아티스트가 입력되지 않았습니다.")
    Integer artistId,

    @NotBlank(message = "앨범명이 입력되지 않았습니다.")
    String title,

    /**
     * 앨범 커버 이미지 파일 번호 (`이미지 업로드` 응답의 file_id)
     */
    @NotNull(message = "앨범 커버 이미지가 입력되지 않았습니다.")
    Integer imageFileId,

    @NotNull(message = "발매일이 지정되지 않았습니다.")
    LocalDateTime releaseDate

) {
    
}
