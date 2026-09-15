package com.osundosun.mupid.api.music.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.osundosun.mupid.api.music.entity.MusicAnalysis;

public interface MusicAnalysisRepository extends JpaRepository<MusicAnalysis, Integer> {

    List<MusicAnalysis> findByMusicIdIn(List<Integer> musicIds);

    /**
     * 분석된 곡 중 보컬이 있는 곡의 비중. MuBTI A축의 기준선이다.
     *
     * 분석되지 않은 곡은 분자·분모 양쪽에서 빠진다. 분모를 전체 곡 수로 잡으면
     * 분석이 덜 된 카탈로그에서 기준선이 0 에 가까워져 모두가 보컬파가 된다.
     *
     * 행이 하나도 없으면 `null` 이다 — 호출부가 그때 A축을 판정하지 않는다.
     */
    @Query("""
        SELECT AVG(CASE WHEN a.hasVocal = true THEN 1.0 ELSE 0.0 END)
        FROM MusicAnalysis a
        """)
    Double findVocalShare();

}
