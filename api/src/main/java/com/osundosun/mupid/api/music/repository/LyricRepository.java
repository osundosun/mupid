package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Lyric;

public interface LyricRepository extends JpaRepository<Lyric, Integer> {
    
}
