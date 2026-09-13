package com.osundosun.mupid.api.music.mapping.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;

public interface MusicGenreRepository extends JpaRepository<MusicGenre, MusicGenre.MusicGenreId> {
    
}
