package com.osundosun.mupid.api.music.mapping.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;

public interface MusicSectionAnalysisMoodRepository extends JpaRepository<
    MusicSectionAnalysisMood, MusicSectionAnalysisMood.MusicSectionAnalysisMoodId
> {

    /** 구간 여러 개의 분위기를 한 번에. 구간마다 조회하면 N+1 이다. */
    List<MusicSectionAnalysisMood> findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
        List<Integer> sectionAnalysisIds);

    /**
     * 구간들에 붙은 분위기 연결을 지운다.
     *
     * **구간을 지우기 전에 이것부터 지운다.** 순서를 바꾸면 FK 가 걸리고, 영속성
     * 컨텍스트에서는 지워진 구간을 가리키는 연결이 남아 `TransientPropertyValueException`
     * 이 난다.
     */
    void deleteByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
        Collection<Integer> sectionAnalysisIds);

    /**
     * 곡들의 구간에 붙은 분위기를 **많이 나온 순서로** 돌려준다.
     *
     * 「오늘의 모먼트」가 묶음의 대표 분위기를 정할 때 쓴다. 첫 번째가 대표다.
     * 같은 수면 번호가 작은 쪽 — 돌릴 때마다 대표가 바뀌면 안 된다.
     *
     * 비어 있을 수 있다. 분위기를 못 고른 곡이 카탈로그의 14% 다.
     */
    @Query("""
        select m.moodCode.codeId
          from MusicSectionAnalysisMood m
         where m.musicSectionAnalysis.music.musicId in :musicIds
         group by m.moodCode.codeId
         order by count(m) desc, m.moodCode.codeId asc
        """)
    List<Integer> findMoodCodeIdsByPopularity(@Param("musicIds") Collection<Integer> musicIds);

}
