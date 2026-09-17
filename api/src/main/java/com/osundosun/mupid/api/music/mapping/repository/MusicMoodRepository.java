package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicMood;

public interface MusicMoodRepository extends JpaRepository<MusicMood, MusicMood.MusicMoodId> {

    /** 곡 목록의 태그를 한 번에 가져온다. */
    List<MusicMood> findByMusicMoodId_MusicIdIn(List<Integer> musicIds);

    /**
     * 한 곡의 분위기를 전부 지운다. 통째 교체의 앞 절반이다.
     *
     * <p>지우고 넣는 것을 한 트랜잭션에서 한다. 나눠 두면 중간에 끊겼을 때
     * 곡의 분위기가 사라진 채 남는다.
     */
    void deleteByMusicMoodId_MusicId(Integer musicId);

}
