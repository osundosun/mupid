package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ArtistCreateRequest(

    @NotBlank(message = "아티스트명이 입력되지 않았습니다.")
    String name,

    /**
     * 아티스트 이미지 파일 번호 (`이미지 업로드` 응답의 file_id)
     */
    @NotNull(message = "아티스트 이미지가 입력되지 않았습니다.")
    Integer imageFileId

) {
    
}
