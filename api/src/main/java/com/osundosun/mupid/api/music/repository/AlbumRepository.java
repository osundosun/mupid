package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Album;

public interface AlbumRepository extends JpaRepository<Album, Integer> {
    
}
