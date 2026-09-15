package com.osundosun.mupid.api.music.request;

import java.time.LocalDateTime;

public record AlbumUpdateRequest(
    
    Integer artistId,

    String title,

    LocalDateTime releaseDate
    
) {
    
}
