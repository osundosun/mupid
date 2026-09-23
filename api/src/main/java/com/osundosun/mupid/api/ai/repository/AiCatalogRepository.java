package com.osundosun.mupid.api.ai.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow;
import com.osundosun.mupid.api.ai.dto.AiCatalogTrackRow;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

/**
 * 추천 서버에 넘길 카탈로그를 읽는다. <b>읽기 전용이고 AI 전용이다.</b>
 *
 * <p>{@code JpaRepository} 가 아니라 {@code Repository} 를 상속한다 — 여기에
 * {@code save}·{@code delete} 가 딸려 오면 "AI 카탈로그 조회" 라는 이름의 저장소로
 * 곡을 지울 수 있게 된다. 이 인터페이스에는 읽는 길만 둔다.
 *
 * <p>한 번의 질의로 다 가져오지 않는다. 곡·장르·무드·구간·악기·순간무드는 전부
 * 1:N 이라 조인하면 곱이 되고, 곡 하나가 장르 2 × 무드 3 × 구간 8 = 48줄로 부푼다.
 * 대신 <b>곡 번호로 페이지를 먼저 자르고</b> 각 표를 {@code IN} 으로 한 번씩 읽어
 * 애플리케이션에서 붙인다 — 질의 수는 페이지당 상수다.
 */
public interface AiCatalogRepository extends Repository<Music, Integer> {

    /**
     * 카탈로그에 실을 곡 번호. <b>삭제된 곡과 구간이 없는 곡은 빼고</b> 번호순으로 준다.
     *
     * <p>구간이 없는 곡을 빼는 것은 추천이 곡이 아니라 <b>구간</b>을 내보내기
     * 때문이다. 구간이 없으면 추천될 수 없으므로 실어 보내도 받는 쪽 메모리만 먹고,
     * 더 나쁘게는 "카탈로그에는 있는데 색인에 없다" 는 불일치로 잘못 집계된다.
     *
     * <p>정렬을 {@code musicId} 로 못 박는다. 페이지를 나누는 조회에서 정렬이
     * 불안정하면 같은 곡이 두 페이지에 나오거나 어느 페이지에도 안 나온다 —
     * 뒤엣것은 카탈로그가 조용히 비는 실패다.
     */
    @Query("""
        SELECT m.musicId FROM Music m
        WHERE m.isDeleted = false
          AND EXISTS (SELECT 1 FROM MusicSectionAnalysis s
                       WHERE s.music.musicId = m.musicId)
        ORDER BY m.musicId ASC
        """)
    Page<Integer> findCatalogMusicIds(Pageable pageable);

    /**
     * 곡 번호·제목·보컬 판정.
     *
     * <p>{@code music_analysis} 는 LEFT JOIN 이다. 분석 행이 없는 곡을 INNER JOIN 으로
     * 떨구면 <b>제목과 장르까지 함께 사라진다</b> — 보컬만 모르는 곡이 카탈로그에서
     * 통째로 빠지는 것은 과한 대가다.
     */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.dto.AiCatalogTrackRow(
            m.musicId, m.title, a.hasVocal, a.vocalGender)
        FROM Music m
        LEFT JOIN MusicAnalysis a ON a.musicId = m.musicId
        WHERE m.musicId IN :musicIds AND m.isDeleted = false
        """)
    List<AiCatalogTrackRow> findTrackRows(@Param("musicIds") List<Integer> musicIds);

    /**
     * 곡 장르. 코드 번호순으로 준다 — 곡마다 순서가 흔들리면 같은 카탈로그가 매번
     * 다른 응답이 되어 받는 쪽이 바뀐 것과 구분하지 못한다.
     */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow(
            g.musicGenreId.musicId, c.codeName)
        FROM MusicGenre g JOIN g.genreCode c
        WHERE g.musicGenreId.musicId IN :musicIds
        ORDER BY g.musicGenreId.musicId ASC, c.codeId ASC
        """)
    List<AiCatalogCodeRow> findGenreRows(@Param("musicIds") List<Integer> musicIds);

    /** 곡 무드. 순간 무드와 <b>다른 축이다</b> — 코드 대역도 다르다. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow(
            d.musicMoodId.musicId, c.codeName)
        FROM MusicMood d JOIN d.moodCode c
        WHERE d.musicMoodId.musicId IN :musicIds
        ORDER BY d.musicMoodId.musicId ASC, c.codeId ASC
        """)
    List<AiCatalogCodeRow> findMoodRows(@Param("musicIds") List<Integer> musicIds);

    /**
     * 곡들의 구간 전부.
     *
     * <p>{@code ORDER BY} 에 {@code index} 를 쓰지 않는다. JPQL 에서 {@code index} 는
     * 예약어라 필드 이름으로 두면 질의가 함수 호출로 해석된다. 시작 시각으로 정렬해도
     * 같은 순서이고(구간은 겹치지 않는다), 순서 번호 자체는 응답에 그대로 싣는다.
     */
    @Query("""
        SELECT s FROM MusicSectionAnalysis s
        WHERE s.music.musicId IN :musicIds
        ORDER BY s.music.musicId ASC, s.startTimeMs ASC
        """)
    List<MusicSectionAnalysis> findSections(@Param("musicIds") List<Integer> musicIds);

    /** 구간별 악기. 소유자는 곡이 아니라 <b>구간</b>이다. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow(
            i.musicAnalysisInstrumentId.musicSectionAnalysisId, c.codeName)
        FROM MusicAnalysisInstrument i JOIN i.instrumentCode c
        WHERE i.musicAnalysisInstrumentId.musicSectionAnalysisId IN :sectionIds
        ORDER BY i.musicAnalysisInstrumentId.musicSectionAnalysisId ASC, c.codeId ASC
        """)
    List<AiCatalogCodeRow> findInstrumentRows(@Param("sectionIds") List<Integer> sectionIds);

    /** 순간 무드. 곡 무드와 코드 대역이 다르므로 합치지 않는다. */
    @Query("""
        SELECT new com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow(
            sm.musicSectionAnalysisMoodId.musicSectionAnalysisId, c.codeName)
        FROM MusicSectionAnalysisMood sm JOIN sm.moodCode c
        WHERE sm.musicSectionAnalysisMoodId.musicSectionAnalysisId IN :sectionIds
        ORDER BY sm.musicSectionAnalysisMoodId.musicSectionAnalysisId ASC, c.codeId ASC
        """)
    List<AiCatalogCodeRow> findSectionMoodRows(@Param("sectionIds") List<Integer> sectionIds);

}
