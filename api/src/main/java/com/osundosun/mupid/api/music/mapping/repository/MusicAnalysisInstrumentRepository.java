package com.osundosun.mupid.api.music.mapping.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;

public interface MusicAnalysisInstrumentRepository extends JpaRepository<
    MusicAnalysisInstrument, MusicAnalysisInstrument.MusicAnalysisInstrumentId
> {

    /** 구간 하나에 달린 악기 코드 이름 한 줄. */
    record SectionInstrument(Integer musicSectionAnalysisId, String codeName) {
    }

    /**
     * 구간들의 악기 <b>코드 이름</b>, 구간 번호·코드 번호순 (S15P21A204-458).
     *
     * <p>ClickHouse {@code track_sections.instruments} 의 원천이다. 순서는 조건 어휘의 후보 질의
     * ({@code RecommenderCatalogRepository.findInstruments})와 같다 — 순서가 흔들리면 같은 구간이 다른
     * 배열이 되어 대조 체크섬({@code SectionDigestRepository})이 매일 어긋난다. {@code other}(그 외
     * 악기)도 거르지 않고 싣는다. 구체적이지 않은 악기를 조건에서 빼는 것은 읽는 쪽(추천 서버) 몫이다.
     */
    @Query("""
        SELECT new com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository$SectionInstrument(
            i.musicAnalysisInstrumentId.musicSectionAnalysisId, c.codeName)
        FROM MusicAnalysisInstrument i JOIN i.instrumentCode c
        WHERE i.musicAnalysisInstrumentId.musicSectionAnalysisId IN :sectionAnalysisIds
        ORDER BY i.musicAnalysisInstrumentId.musicSectionAnalysisId ASC, c.codeId ASC
        """)
    List<SectionInstrument> findCodeNamesBySectionIds(
        @Param("sectionAnalysisIds") List<Integer> sectionAnalysisIds);

    /** 곡과 그 구간의 분석 버전. 미반영 기록({@code TrackSectionSync.markPending})이 둘 다 받는다. */
    record MusicVersion(Integer musicId, String analysisVersion) {
    }

    /**
     * 이 코드를 단 구간이 있는 곡들. 관리자 화면에서 악기 코드 이름을 고치면 그 곡들의
     * {@code track_sections.instruments} 가 바뀐다.
     */
    @Query("""
        SELECT DISTINCT new com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository$MusicVersion(
            s.music.musicId, s.analysisVersion)
        FROM MusicAnalysisInstrument i JOIN i.musicSectionAnalysis s
        WHERE i.instrumentCode.codeId = :codeId
        """)
    List<MusicVersion> findMusicUsingCode(@Param("codeId") int codeId);

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
