package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

public record MusicUpdateRequest(

    Integer albumId,

    Integer artistId,

    String title,

    LocalDateTime releaseDate

) {
    
}
