package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.response.AlbumDetailResponse;
import com.osundosun.mupid.api.music.response.AlbumPageResponse;
import com.osundosun.mupid.api.music.request.AlbumCreateRequest;
import com.osundosun.mupid.api.music.request.AlbumUpdateRequest;

public interface AlbumService {

    int createAlbum(AlbumCreateRequest request);

    Optional<Album> getAlbum(int albumId);

    /**
     * 목록·검색. 앨범명과 아티스트명 둘 다 본다.
     *
     * @param artistId 주면 그 아티스트의 앨범만. null 이면 전체
     */
    AlbumPageResponse searchAlbums(String keyword, Integer artistId, int page, int size);

    /** 상세. 수록곡이 함께 온다. */
    AlbumDetailResponse getAlbumDetail(int albumId);

    void updateAlbum(int albumId, AlbumUpdateRequest request);

    void deleteAlbum(int albumId);
    
}
