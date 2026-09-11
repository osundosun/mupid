package com.osundosun.mupid.api.playlist.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaylistCreateRequest(

    @Size(min = 1, max = 100, message = "플레이리스트 제목은 1~100자 사이어야 합니다.")
    @NotBlank(message = "플레이리스트 제목이 입력되지 않았습니다.")
    String title

) {
    
}
