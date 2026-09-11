package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.NotBlank;

public record ArtistCreateRequest(

    @NotBlank(message = "아티스트명이 입력되지 않았습니다.")
    String name

) {
    
}
