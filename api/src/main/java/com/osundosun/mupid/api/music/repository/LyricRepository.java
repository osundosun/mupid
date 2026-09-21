package com.osundosun.mupid.api.music.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 한 곡의 가사에서 **가장 늦은 시점**(ms). 가사가 없으면 null.
     *
     * <p>타임라인의 가로축 최대값을 정하는 데 쓴다(S15P21A204-380-B). 행을 전부 받아 Java
     * 에서 훑으면 밴드가 잘렸을 때 축이 함께 줄어들어, 화면이 보이는 항목만으로 축을
     * 그리게 된다 — 그러면 같은 곡인데도 밴드마다 눈금이 달라 보인다. DB 에서 집계하면
     * 잘림과 무관하게 곡 전체 기준으로 고정된다.
     */
    @Query("SELECT MAX(l.timeMs) FROM Lyric l WHERE l.music.musicId = :musicId")
    Integer maxTimeMsOfMusic(@Param("musicId") Integer musicId);

}
