package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Artist;

public interface ArtistService {

    int createArtist(String name);

    Optional<Artist> getArtist(int artistId);

    boolean renameArtist(int artistId, String name);

    void deleteArtist(int artistId);
    
}
