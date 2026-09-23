package com.osundosun.mupid.api.ai.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.VocalGender;

/**
 * 추천 후보를 읽는다. <b>읽기 전용이다.</b>
 *
 * <p>{@code JpaRepository} 가 아니라 {@code Repository} 를 상속한다 — {@code save}·
 * {@code delete} 가 딸려 오면 "추천 후보 조회" 라는 이름의 저장소로 곡을 지울 수 있게 된다.
 *
 * <p>카탈로그가 곡 1천 남짓이라 <b>표마다 한 번씩 통째로</b> 읽어 애플리케이션에서 붙인다.
 * 1:N 표를 조인 하나로 묶으면 곱이 되고(장르 2 × 무드 3 × 구간 8 = 48줄), 곡 번호를
 * {@code IN} 으로 넘기면 구간 번호 9천 개를 바인딩하게 된다.
 *
 * <p>후보의 조건은 한 곳에서만 건다 — {@link #findCandidateMusic()}. 지워지지 않았고,
 * 재생할 스트림({@code DONE})이 있고, 구간이 있는 곡이다. 나머지 질의는 그 결과로 거른다.
 */
public interface RecommenderCatalogRepository extends Repository<Music, Integer> {

    /** 곡 번호와 곡 길이. 곡 길이는 완료된 스트림의 것이다(추천 서버가 곡 안 위치를 센다). */
    record CandidateRow(Integer musicId, Integer durationMs) {
    }

    /** 곡 분석 값. 행이 없는 곡도 있다(분석 전). */
    record AnalysisRow(Integer musicId, Boolean hasVocal, VocalGender vocalGender, Double bpm,
                       Integer bpmRequestedMin, Integer bpmRequestedMax, String language,
                       String category) {
    }

    /** 무엇인가에 코드 이름 하나가 달린 줄. {@code ownerId} 는 곡 번호이기도, 구간 번호이기도 하다. */
    record CodeRow(Integer ownerId, String codeName) {
    }

    @Query("""
        SELECT new com.osundosun.mupid.api.ai.catalog.RecommenderCatalogRepository$CandidateRow(
            m.musicId, MAX(st.durationMs))
        FROM Stream st JOIN st.music m
        WHERE m.isDeleted = false
          AND st.status = com.osundosun.mupid.api.stream.enumerate.StreamStatus.DONE
          AND EXISTS (SELECT 1 FROM MusicSectionAnalysis s WHERE s.music.musicId = m.musicId)
        GROUP BY m.musicId
        ORDER BY m.musicId ASC
        """)
    List<CandidateRow> findCandidateMusic();

    @Query("""
        SELECT new com.osundosun.mupid.api.ai.catalog.RecommenderCatalogRepository$AnalysisRow(
            a.musicId, a.hasVocal, a.vocalGender, a.bpm, a.bpmRequestedMin, a.bpmRequestedMax,
            a.language, a.category)
        FROM MusicAnalysis a
        """)
    List<AnalysisRow> findAnalysis();

    /** 곡 장르. 코드 번호순이다 — 순서가 흔들리면 같은 카탈로그가 다른 본문이 된다. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.catalog.RecommenderCatalogRepository$CodeRow(
            g.musicGenreId.musicId, c.codeName)
        FROM MusicGenre g JOIN g.genreCode c
        ORDER BY g.musicGenreId.musicId ASC, c.codeId ASC
        """)
    List<CodeRow> findGenres();

    /** 곡 무드. 구간 무드와 <b>다른 축이다</b>. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.catalog.RecommenderCatalogRepository$CodeRow(
            d.musicMoodId.musicId, c.codeName)
        FROM MusicMood d JOIN d.moodCode c
        ORDER BY d.musicMoodId.musicId ASC, c.codeId ASC
        """)
    List<CodeRow> findMoods();

    /**
     * 구간 전부. {@code ORDER BY} 에 {@code index} 를 쓰지 않는다 — JPQL 예약어라 함수
     * 호출로 해석된다. 구간은 겹치지 않으므로 시작 시각 순서가 같다.
     */
    @Query("""
        SELECT s FROM MusicSectionAnalysis s JOIN FETCH s.music m
        WHERE m.isDeleted = false
        ORDER BY m.musicId ASC, s.startTimeMs ASC
        """)
    List<MusicSectionAnalysis> findSections();

    /** 구간별 악기. 소유자는 곡이 아니라 <b>구간</b>이다. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.catalog.RecommenderCatalogRepository$CodeRow(
            i.musicAnalysisInstrumentId.musicSectionAnalysisId, c.codeName)
        FROM MusicAnalysisInstrument i JOIN i.instrumentCode c
        ORDER BY i.musicAnalysisInstrumentId.musicSectionAnalysisId ASC, c.codeId ASC
        """)
    List<CodeRow> findInstruments();

}
