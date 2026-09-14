package com.osundosun.mupid.api.playlist.dto;

import java.time.LocalDateTime;

public record PlaylistResponseItem(

    Integer playlistId,

    String title,

    Integer musicCount,

    LocalDateTime createdAt,

    LocalDateTime updatedAt

) {

}
