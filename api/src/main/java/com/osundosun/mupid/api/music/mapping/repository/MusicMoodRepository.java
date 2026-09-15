package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicMood;

public interface MusicMoodRepository extends JpaRepository<MusicMood, MusicMood.MusicMoodId> {

    /** 곡 목록의 태그를 한 번에 가져온다. */
    List<MusicMood> findByMusicMoodId_MusicIdIn(List<Integer> musicIds);

}
