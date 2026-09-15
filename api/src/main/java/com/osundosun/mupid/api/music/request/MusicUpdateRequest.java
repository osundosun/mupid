package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

public record MusicUpdateRequest(

    Integer albumId,

    Integer artistId,

    String title,

    LocalDateTime releaseDate,

    /** 곡 커버 이미지 파일 번호. null 이면 지금 커버를 그대로 둔다. */
    Integer imageFileId

) {
    
}
