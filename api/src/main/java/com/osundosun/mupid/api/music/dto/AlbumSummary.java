package com.osundosun.mupid.api.music.dto;

import java.time.LocalDateTime;

/** 목록이 쓰는 앨범 표현. 수록곡은 상세에서만 붙는다. */
public record AlbumSummary(
    Integer albumId,
    String title,
    String imageUrl,
    LocalDateTime releaseDate,
    ArtistSummary artist
) {

}
