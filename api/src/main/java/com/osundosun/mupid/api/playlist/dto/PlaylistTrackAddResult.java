package com.osundosun.mupid.api.playlist.dto;

import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;

/**
 * 곡 추가 결과.
 *
 * 중복은 오류가 아니라 정상 응답이라 예외 대신 이 플래그로 구분한다.
 * duplicated 가 true 면 track 은 이미 담겨 있던 수록곡이다.
 */
public record PlaylistTrackAddResult(

    PlaylistTrack track,

    boolean duplicated

) {

}
