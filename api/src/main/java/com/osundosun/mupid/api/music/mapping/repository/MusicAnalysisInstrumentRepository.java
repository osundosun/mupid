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

    /**
     * 구간을 지우기 전에 그 구간에 달린 악기를 먼저 끊는다.
     *
     * 외래키가 `music_section_analysis_id` 라 순서를 지키지 않으면 구간 DELETE 가
     * 제약에 걸린다 — 분위기(`MusicSectionAnalysisMoodRepository`)와 같은 이유다.
     */
    void deleteByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(
        List<Integer> sectionAnalysisIds);

}
