package com.osundosun.mupid.api.music.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Lyric;

public interface LyricRepository extends JpaRepository<Lyric, Integer> {

    /** 한 곡의 가사. 시간 오름차순 — 화면이 위에서 아래로 흘려 보여준다. */
    List<Lyric> findByMusic_MusicIdOrderByTimeMsAscLyricIdAsc(Integer musicId);

}
