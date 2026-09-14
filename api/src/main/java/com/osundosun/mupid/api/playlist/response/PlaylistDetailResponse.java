package com.osundosun.mupid.api.playlist.response;

import java.time.LocalDateTime;
import java.util.List;

import com.osundosun.mupid.api.playlist.dto.PlaylistTrackResponseItem;

public record PlaylistDetailResponse(

    Integer playlistId,

    String title,

    /**
     * 역정규화된 수록곡 수. 삭제된 곡이 담겨 있으면 tracks 의 길이와 다를 수 있다.
     */
    Integer musicCount,

    LocalDateTime createdAt,

    LocalDateTime updatedAt,

    List<PlaylistTrackResponseItem> tracks

) {

}
