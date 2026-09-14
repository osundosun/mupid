package com.osundosun.mupid.api.playlist.dto;

import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;

/**
 * 수록곡 하나와 재생 길이.
 *
 * 길이는 music 이 아니라 streams 에 있어서 엔티티만으로는 채울 수 없다.
 * 아직 인코딩되지 않은 곡은 null 이다.
 */
public record PlaylistTrackDetail(

    PlaylistTrack track,

    Integer durationMs

) {

}
