package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.request.AlbumCreateRequest;
import com.osundosun.mupid.api.music.request.AlbumUpdateRequest;

public interface AlbumService {

    int createAlbum(AlbumCreateRequest request);

    Optional<Album> getAlbum(int albumId);

    void updateAlbum(int albumId, AlbumUpdateRequest request);

    void deleteAlbum(int albumId);
    
}
