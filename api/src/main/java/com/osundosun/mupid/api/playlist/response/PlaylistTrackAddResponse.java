package com.osundosun.mupid.api.playlist.response;

import java.time.LocalDateTime;

public record PlaylistTrackAddResponse(

    Integer playlistId,

    Integer musicId,

    Integer trackOrder,

    Integer musicCount,

    /**
     * 이미 담겨 있던 곡이면 true. 이 경우 아무것도 추가되지 않았고
     * trackOrder / createdAt 은 기존 수록곡의 값이다.
     */
    Boolean duplicated,

    LocalDateTime createdAt

) {

}
