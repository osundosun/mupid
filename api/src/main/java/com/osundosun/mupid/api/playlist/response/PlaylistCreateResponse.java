package com.osundosun.mupid.api.playlist.response;

import java.time.LocalDateTime;

public record PlaylistCreateResponse(

    Integer playlistId,

    String title,

    Integer musicCount,

    LocalDateTime createdAt

) {
    
}
