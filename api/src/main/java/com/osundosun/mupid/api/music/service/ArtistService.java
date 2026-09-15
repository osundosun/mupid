package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.dto.ArtistSummary;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.response.ArtistPageResponse;
import com.osundosun.mupid.api.music.request.ArtistCreateRequest;

public interface ArtistService {

    int createArtist(ArtistCreateRequest request);

    Optional<Artist> getArtist(int artistId);

    /** 목록·검색. 검색어가 비면 전체다. */
    ArtistPageResponse searchArtists(String keyword, int page, int size);

    /** 상세. 삭제된 아티스트는 404 다. */
    ArtistSummary getArtistSummary(int artistId);

    boolean renameArtist(int artistId, String name);

    void deleteArtist(int artistId);
    
}
