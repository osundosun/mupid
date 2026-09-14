package com.osundosun.mupid.api.playlist.dto;

import java.util.List;

import com.osundosun.mupid.api.playlist.entity.Playlist;

public record PlaylistDetail(

    Playlist playlist,

    List<PlaylistTrackDetail> tracks

) {

}
