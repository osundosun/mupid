package com.osundosun.mupid.api.playlist.dto;

import java.time.LocalDateTime;

import com.osundosun.mupid.api.common.enumerate.Selector;

public record PlaylistTrackResponseItem(

    Integer trackOrder,

    Integer musicId,

    String title,

    String artistName,

    String albumTitle,

    /**
     * 재생 길이. 아직 인코딩되지 않은 곡은 null 이다.
     */
    Integer durationMs,

    Selector selector,

    LocalDateTime addedAt

) {

}
