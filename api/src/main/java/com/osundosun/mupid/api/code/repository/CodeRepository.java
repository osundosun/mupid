package com.osundosun.mupid.api.code.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;

public interface CodeRepository extends JpaRepository<Code, Integer> {

    /** 화면에 뿌릴 목록. 등록 순서를 그대로 쓴다. */
    List<Code> findAllByCodeTypeOrderByCodeIdAsc(CodeType codeType);

    /**
     * 번호 구간을 정해 읽는다. 무드를 동결 구간({@link com.osundosun.mupid.api.code.MoodCodeIds})
     * 으로 좁혀 읽을 때 쓴다.
     *
     * <p>용도만 걸고 전량을 읽으면 안 되는 자리가 있다. 운영 DB 의 {@code codes} 에는
     * 같은 무드가 옛 중복 적재로 여러 번호에 남아 있고(98~132), 그대로 내놓으면 화면에
     * 같은 분위기가 두 칩으로 뜬다. 사유는
     * {@code knowledge/decision/0025-mood-codes-32-48-are-frozen.md}.
     */
    List<Code> findAllByCodeTypeAndCodeIdBetweenOrderByCodeIdAsc(
        CodeType codeType, Integer startCodeId, Integer endCodeId);

    /**
     * 탐색 사이드바에 내놓을 분위기 — <b>곡이 실제로 달린 코드만</b>.
     *
     * <p>코드표에는 있지만 어느 곡에도 붙지 않은 분위기가 있다. 2026-09-21 기준으로
     * 대분류 8종(`serene`~`romantic`)이 그렇다 — 적재 파이프라인이 taxonomy 9종
     * (`melancholic`~`exciting`)만 곡에 단다. 그것까지 칩으로 내놓으면 <b>눌러도
     * 결과가 0건</b>이라 사용자는 검색이 고장 난 것으로 읽는다.
     *
     * <p>조건을 검색 쿼리와 맞춘다 — 삭제된 곡은 {@code MusicRepository} 의
     * {@code m.isDeleted = false} 에 걸려 결과에 없으므로 여기서도 세지 않는다.
     * 그러지 않으면 마지막 곡이 지워진 분위기가 칩으로 남는다.
     *
     * <p><b>온보딩 선택지는 이것을 쓰지 않는다.</b> 그쪽은 카탈로그 검색이 아니라
     * 취향을 묻는 자리라 코드표 전량(17종)을 그대로 보여 준다
     * ({@code OnboardingServiceImpl.getOptions}).
     */
    @Query("""
        SELECT c FROM Code c
        WHERE c.codeType = com.osundosun.mupid.api.code.enumerate.CodeType.MOOD
          AND EXISTS (
            SELECT mm.musicMoodId.musicId FROM MusicMood mm
            WHERE mm.moodCode = c
              AND EXISTS (
                SELECT m.musicId FROM Music m
                WHERE m.musicId = mm.musicMoodId.musicId AND m.isDeleted = false))
        ORDER BY c.codeId ASC
        """)
    List<Code> findMoodsInUse();

    /** 탐색 사이드바에 내놓을 장르 — 곡이 실제로 달린 코드만. {@link #findMoodsInUse()} 와 같은 이유다. */
    @Query("""
        SELECT c FROM Code c
        WHERE c.codeType = com.osundosun.mupid.api.code.enumerate.CodeType.GENRE
          AND EXISTS (
            SELECT mg.musicGenreId.musicId FROM MusicGenre mg
            WHERE mg.genreCode = c
              AND EXISTS (
                SELECT m.musicId FROM Music m
                WHERE m.musicId = mg.musicGenreId.musicId AND m.isDeleted = false))
        ORDER BY c.codeId ASC
        """)
    List<Code> findGenresInUse();

    List<Code> findAllByCodeIdIn(List<Integer> codeIds);

    /** 리포트 키워드의 코드명에 대응하는 화면 표시명을 한 번에 읽는다. */
    List<Code> findAllByCodeTypeAndCodeNameIn(CodeType codeType, Set<String> codeNames);

    /**
     * 종류와 이름으로 하나.
     *
     * 표의 UNIQUE 키가 `(code_type, code_name)` 이라 최대 하나다. 코드 추가가
     * 멱등이어야 해서 필요하다 — 배치가 같은 값을 두 번 보내도 새로 만들지 않는다.
     */
    Optional<Code> findByCodeTypeAndCodeName(CodeType codeType, String codeName);

}
