package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;

public interface MusicAnalysisInstrumentRepository extends JpaRepository<
    MusicAnalysisInstrument, MusicAnalysisInstrument.MusicAnalysisInstrumentId
> {

    /**
     * 구간 여러 개의 악기를 한 번에.
     *
     * 악기는 곡이 아니라 **구간**에 달린다 (`music_analysis_instruments` 의
     * 외래키가 `music_section_analysis_id` 다).
     */
    List<MusicAnalysisInstrument> findByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(
        List<Integer> sectionAnalysisIds);

}
