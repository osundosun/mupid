package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Music;

public interface MusicRepository extends JpaRepository<Music, Integer> {
    
}
