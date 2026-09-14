package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Artist;

public interface ArtistRepository extends JpaRepository<Artist, Integer> {
    
}
