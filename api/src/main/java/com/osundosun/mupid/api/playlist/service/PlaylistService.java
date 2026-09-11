package com.osundosun.mupid.api.playlist.service;

import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;

public interface PlaylistService {

    Playlist createPlaylist(int userId, PlaylistCreateRequest dto);
    
}
