package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

public record MusicAddRequest(

    Integer albumId,

    Integer artistId,

    String title,

    LocalDateTime releaseDate

) {
    
}
