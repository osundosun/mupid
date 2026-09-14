package com.osundosun.mupid.api.playlist.response;

import java.util.List;

import com.osundosun.mupid.api.playlist.dto.PlaylistResponseItem;

public record PlaylistGetResponse(

    List<PlaylistResponseItem> playlists

) {

}
