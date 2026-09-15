package com.osundosun.mupid.api.music.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.Lyric;

public interface LyricRepository extends JpaRepository<Lyric, Integer> {

    /** 한 곡의 가사. 시간 오름차순 — 화면이 위에서 아래로 흘려 보여준다. */
    List<Lyric> findByMusic_MusicIdOrderByTimeMsAscLyricIdAsc(Integer musicId);

    /**
     * 한 곡의 가사를 통째로 지운다. 지운 줄 수를 돌려준다.
     *
     * 일괄 등록이 "지우고 다시 쓰기" 라서 필요하다. 줄마다 `delete` 를 부르면
     * 영속성 컨텍스트에 수십 개를 올렸다 지우는 셈이 된다.
     */
    int deleteByMusic_MusicId(Integer musicId);

}
