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
    LocalDateTime releaseDate,

    /**
     * 곡 커버 이미지 파일 번호 (`이미지 업로드` 응답의 file_id). **선택이다.**
     *
     * 앨범 커버(`album.image_file_id`)와 달리 비워 둘 수 있다. 비우면 화면이
     * 앨범 커버를 대신 쓴다.
     */
    Integer imageFileId

) {
    
}
