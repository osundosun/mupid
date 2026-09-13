package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.request.ArtistCreateRequest;

public interface ArtistService {

    int createArtist(ArtistCreateRequest request);

    Optional<Artist> getArtist(int artistId);

    boolean renameArtist(int artistId, String name);

    void deleteArtist(int artistId);
    
}
