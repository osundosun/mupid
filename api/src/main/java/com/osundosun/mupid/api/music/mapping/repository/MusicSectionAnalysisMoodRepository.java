package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;

public interface MusicSectionAnalysisMoodRepository extends JpaRepository<
    MusicSectionAnalysisMood, MusicSectionAnalysisMood.MusicSectionAnalysisMoodId
> {

    /** 구간 여러 개의 분위기를 한 번에. 구간마다 조회하면 N+1 이다. */
    List<MusicSectionAnalysisMood> findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
        List<Integer> sectionAnalysisIds);

}
