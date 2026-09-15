package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;

public interface MusicGenreRepository extends JpaRepository<MusicGenre, MusicGenre.MusicGenreId> {

    /** 곡 목록의 태그를 한 번에 가져온다. */
    List<MusicGenre> findByMusicGenreId_MusicIdIn(List<Integer> musicIds);

}
