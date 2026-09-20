package com.osundosun.mupid.api.music.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.Music;

public interface MusicRepository extends JpaRepository<Music, Integer> {

    /**
     * 검색·목록이 함께 쓰는 보컬 필터 조각.
     *
     * <h2>왜 LEFT JOIN 인가</h2>
     *
     * {@code music_analysis} 는 <b>분석이 끝난 곡에만</b> 행이 있다. INNER JOIN 으로
     * 붙이면 필터를 안 걸었을 때조차 분석 행이 없는 곡이 전부 사라진다 — 개발 DB 는
     * 분석 행이 0 이라 전곡이 사라진다. 그래서 항상 LEFT JOIN 이다.
     *
     * <h2>왜 {@code NOT :filtered} 가드가 필요한가</h2>
     *
     * 필터를 안 걸었을 때(모든 값이 false) 첫 항이 참이 되어 나머지 가지가 통째로
     * 무시된다. 별도 쿼리를 두지 않고도 "필터가 없으면 예전과 완전히 같은 결과" 를
     * 보장한다.
     *
     * <h2>'인스트루멘탈' 은 성별이 아니라 유무다</h2>
     *
     * {@code has_vocal = false} 로 표현한다. {@code VocalGender} 에 '보컬 없음' 값이
     * 없기 때문이다(자세한 근거는 {@code VocalFilter} 주석). {@code has_vocal = true}
     * 혼성 보컬은 {@code vocal_gender = MIX} 로 별도 선택한다.
     *
     * <h2>여러 개를 고르면 OR 다</h2>
     *
     * 한 곡이 여성이면서 동시에 인스트루멘탈일 수 없어 교집합(AND)은 언제나 0 이다.
     */
    String VOCAL_FILTER = """
          AND (:filtered = false
               OR (:wantFemale = true AND ma.hasVocal = true AND ma.vocalGender = com.osundosun.mupid.api.music.enumerate.VocalGender.F)
               OR (:wantMale = true AND ma.hasVocal = true AND ma.vocalGender = com.osundosun.mupid.api.music.enumerate.VocalGender.M)
               OR (:wantMixed = true AND ma.hasVocal = true AND ma.vocalGender = com.osundosun.mupid.api.music.enumerate.VocalGender.MIX)
               OR (:wantInstrumental = true AND ma.hasVocal = false))
        """;

    String TAXONOMY_FILTER = """
          AND (:moodFiltered = false OR EXISTS (
              SELECT mm.musicMoodId.musicId FROM MusicMood mm
              WHERE mm.musicMoodId.musicId = m.musicId
                AND mm.moodCode.codeName IN :moodNames))
          AND (:genreFiltered = false OR EXISTS (
              SELECT mg.musicGenreId.musicId FROM MusicGenre mg
              WHERE mg.musicGenreId.musicId = m.musicId
                AND mg.genreCode.codeName IN :genreNames))
        """;

    /** 전체 목록. 검색어가 있을 때는 아래 인덱스 검색으로 ID를 먼저 찾는다. */
    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
        LEFT JOIN FETCH m.imageFile
        LEFT JOIN MusicAnalysis ma ON ma.musicId = m.musicId
        WHERE m.isDeleted = false
        """ + VOCAL_FILTER + TAXONOMY_FILTER)
    Page<Music> findPageFiltered(
        @Param("filtered") boolean filtered,
        @Param("wantFemale") boolean wantFemale,
        @Param("wantMale") boolean wantMale,
        @Param("wantMixed") boolean wantMixed,
        @Param("wantInstrumental") boolean wantInstrumental,
        @Param("moodFiltered") boolean moodFiltered,
        @Param("moodNames") List<String> moodNames,
        @Param("genreFiltered") boolean genreFiltered,
        @Param("genreNames") List<String> genreNames,
        Pageable pageable);

    /** 기존 호출부가 쓰는 필터 없는 목록 계약. */
    default Page<Music> findPage(
        boolean filtered,
        boolean wantFemale,
        boolean wantMale,
        boolean wantInstrumental,
        Pageable pageable
    ) {
        return findPageFiltered(
            filtered, wantFemale, wantMale, false, wantInstrumental,
            false, List.of("__NO_FILTER__"), false, List.of("__NO_FILTER__"), pageable);
    }

    /**
     * 곡 제목·아티스트명·앨범명을 각 ngram FULLTEXT 인덱스에서 찾고 관련도순으로
     * 합친다. 테이블별 UNION 은 서로 다른 전문 인덱스를 확실히 타게 하기 위함이다.
     *
     * <h2>보컬 필터는 UNION 바깥에서 건다</h2>
     *
     * 각 UNION 가지 안에서 조인하면 <b>인덱스가 세 번 조인된다</b>. 곡 번호가 이미
     * 좁혀진 뒤에 한 번만 붙이는 편이 낫다. {@code ranked} 는 곡 번호당 여러 행일 수
     * 있으므로 LEFT JOIN 은 <b>집계 전에</b> 걸고, 필터도 WHERE 에서 함께 건다.
     *
     * <h2>countQuery 를 같이 고쳐야 한다</h2>
     * 둘 중 하나만 고치면 페이지 수와 실제 결과 수가 어긋난다. 아래 countQuery 도
     * 같은 LEFT JOIN 과 같은 필터를 갖는다 — <b>이 지점이 빠뜨리기 가장 쉽다.</b>
     *
     * <h2>필터가 없으면</h2>
     * `:filtered = false` 가 참이 되어 예전과 완전히 같은 결과가 나온다.
     */
    @Query(value = """
        SELECT ranked.music_id
        FROM (
            SELECT m.music_id, MATCH(m.title) AGAINST (:query IN BOOLEAN MODE) * 3 AS score
            FROM music m
            WHERE m.is_deleted = false
              AND MATCH(m.title) AGAINST (:query IN BOOLEAN MODE)
            UNION ALL
            SELECT m.music_id, MATCH(a.name) AGAINST (:query IN BOOLEAN MODE) * 2 AS score
            FROM artists a
            JOIN music m ON m.artist_id = a.artist_id AND m.is_deleted = false
            WHERE a.is_deleted = false
              AND MATCH(a.name) AGAINST (:query IN BOOLEAN MODE)
            UNION ALL
            SELECT m.music_id, MATCH(al.title) AGAINST (:query IN BOOLEAN MODE) AS score
            FROM albums al
            JOIN music m ON m.album_id = al.album_id AND m.is_deleted = false
            WHERE al.is_deleted = false
              AND MATCH(al.title) AGAINST (:query IN BOOLEAN MODE)
        ) ranked
        LEFT JOIN music_analysis ma ON ma.music_id = ranked.music_id
        WHERE (:filtered = false
               OR (:wantFemale = true AND ma.has_vocal = true AND ma.vocal_gender = 'F')
               OR (:wantMale = true AND ma.has_vocal = true AND ma.vocal_gender = 'M')
               OR (:wantMixed = true AND ma.has_vocal = true AND ma.vocal_gender = 'MIX')
               OR (:wantInstrumental = true AND ma.has_vocal = false))
          AND (:moodFiltered = false OR EXISTS (
              SELECT 1 FROM music_moods mm
              JOIN codes mood_code ON mood_code.code_id = mm.mood_code_id
              WHERE mm.music_id = ranked.music_id AND mood_code.code_name IN (:moodNames)))
          AND (:genreFiltered = false OR EXISTS (
              SELECT 1 FROM music_genres mg
              JOIN codes genre_code ON genre_code.code_id = mg.genre_code_id
              WHERE mg.music_id = ranked.music_id AND genre_code.code_name IN (:genreNames)))
        GROUP BY ranked.music_id
        ORDER BY MAX(ranked.score) DESC, ranked.music_id DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT ranked.music_id)
        FROM (
            SELECT m.music_id FROM music m
            WHERE m.is_deleted = false
              AND MATCH(m.title) AGAINST (:query IN BOOLEAN MODE)
            UNION ALL
            SELECT m.music_id FROM artists a
            JOIN music m ON m.artist_id = a.artist_id AND m.is_deleted = false
            WHERE a.is_deleted = false
              AND MATCH(a.name) AGAINST (:query IN BOOLEAN MODE)
            UNION ALL
            SELECT m.music_id FROM albums al
            JOIN music m ON m.album_id = al.album_id AND m.is_deleted = false
            WHERE al.is_deleted = false
              AND MATCH(al.title) AGAINST (:query IN BOOLEAN MODE)
        ) ranked
        LEFT JOIN music_analysis ma ON ma.music_id = ranked.music_id
        WHERE (:filtered = false
               OR (:wantFemale = true AND ma.has_vocal = true AND ma.vocal_gender = 'F')
               OR (:wantMale = true AND ma.has_vocal = true AND ma.vocal_gender = 'M')
               OR (:wantMixed = true AND ma.has_vocal = true AND ma.vocal_gender = 'MIX')
               OR (:wantInstrumental = true AND ma.has_vocal = false))
          AND (:moodFiltered = false OR EXISTS (
              SELECT 1 FROM music_moods mm
              JOIN codes mood_code ON mood_code.code_id = mm.mood_code_id
              WHERE mm.music_id = ranked.music_id AND mood_code.code_name IN (:moodNames)))
          AND (:genreFiltered = false OR EXISTS (
              SELECT 1 FROM music_genres mg
              JOIN codes genre_code ON genre_code.code_id = mg.genre_code_id
              WHERE mg.music_id = ranked.music_id AND genre_code.code_name IN (:genreNames)))
        """,
        nativeQuery = true)
    Page<Integer> searchIdsFullText(
        @Param("query") String query,
        @Param("filtered") boolean filtered,
        @Param("wantFemale") boolean wantFemale,
        @Param("wantMale") boolean wantMale,
        @Param("wantMixed") boolean wantMixed,
        @Param("wantInstrumental") boolean wantInstrumental,
        @Param("moodFiltered") boolean moodFiltered,
        @Param("moodNames") List<String> moodNames,
        @Param("genreFiltered") boolean genreFiltered,
        @Param("genreNames") List<String> genreNames,
        Pageable pageable);

    /** ngram 최소 토큰보다 짧은 한 글자는 B-Tree prefix 검색으로 처리한다. */
    @Query(value = """
        SELECT ranked.music_id
        FROM (
            SELECT m.music_id, 3 AS weight FROM music m
            WHERE m.is_deleted = false
              AND m.title LIKE CONCAT(:prefix, '%')
            UNION ALL
            SELECT m.music_id, 2 AS weight FROM artists a
            JOIN music m ON m.artist_id = a.artist_id AND m.is_deleted = false
            WHERE a.is_deleted = false
              AND a.name LIKE CONCAT(:prefix, '%')
            UNION ALL
            SELECT m.music_id, 1 AS weight FROM albums al
            JOIN music m ON m.album_id = al.album_id AND m.is_deleted = false
            WHERE al.is_deleted = false
              AND al.title LIKE CONCAT(:prefix, '%')
        ) ranked
        LEFT JOIN music_analysis ma ON ma.music_id = ranked.music_id
        WHERE (:filtered = false
               OR (:wantFemale = true AND ma.has_vocal = true AND ma.vocal_gender = 'F')
               OR (:wantMale = true AND ma.has_vocal = true AND ma.vocal_gender = 'M')
               OR (:wantMixed = true AND ma.has_vocal = true AND ma.vocal_gender = 'MIX')
               OR (:wantInstrumental = true AND ma.has_vocal = false))
          AND (:moodFiltered = false OR EXISTS (
              SELECT 1 FROM music_moods mm
              JOIN codes mood_code ON mood_code.code_id = mm.mood_code_id
              WHERE mm.music_id = ranked.music_id AND mood_code.code_name IN (:moodNames)))
          AND (:genreFiltered = false OR EXISTS (
              SELECT 1 FROM music_genres mg
              JOIN codes genre_code ON genre_code.code_id = mg.genre_code_id
              WHERE mg.music_id = ranked.music_id AND genre_code.code_name IN (:genreNames)))
        GROUP BY ranked.music_id
        ORDER BY MAX(ranked.weight) DESC, ranked.music_id DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT ranked.music_id)
        FROM (
            SELECT m.music_id FROM music m
            WHERE m.is_deleted = false
              AND m.title LIKE CONCAT(:prefix, '%')
            UNION ALL
            SELECT m.music_id FROM artists a
            JOIN music m ON m.artist_id = a.artist_id AND m.is_deleted = false
            WHERE a.is_deleted = false
              AND a.name LIKE CONCAT(:prefix, '%')
            UNION ALL
            SELECT m.music_id FROM albums al
            JOIN music m ON m.album_id = al.album_id AND m.is_deleted = false
            WHERE al.is_deleted = false
              AND al.title LIKE CONCAT(:prefix, '%')
        ) ranked
        LEFT JOIN music_analysis ma ON ma.music_id = ranked.music_id
        WHERE (:filtered = false
               OR (:wantFemale = true AND ma.has_vocal = true AND ma.vocal_gender = 'F')
               OR (:wantMale = true AND ma.has_vocal = true AND ma.vocal_gender = 'M')
               OR (:wantMixed = true AND ma.has_vocal = true AND ma.vocal_gender = 'MIX')
               OR (:wantInstrumental = true AND ma.has_vocal = false))
          AND (:moodFiltered = false OR EXISTS (
              SELECT 1 FROM music_moods mm
              JOIN codes mood_code ON mood_code.code_id = mm.mood_code_id
              WHERE mm.music_id = ranked.music_id AND mood_code.code_name IN (:moodNames)))
          AND (:genreFiltered = false OR EXISTS (
              SELECT 1 FROM music_genres mg
              JOIN codes genre_code ON genre_code.code_id = mg.genre_code_id
              WHERE mg.music_id = ranked.music_id AND genre_code.code_name IN (:genreNames)))
        """,
        nativeQuery = true)
    Page<Integer> searchIdsByPrefix(
        @Param("prefix") String prefix,
        @Param("filtered") boolean filtered,
        @Param("wantFemale") boolean wantFemale,
        @Param("wantMale") boolean wantMale,
        @Param("wantMixed") boolean wantMixed,
        @Param("wantInstrumental") boolean wantInstrumental,
        @Param("moodFiltered") boolean moodFiltered,
        @Param("moodNames") List<String> moodNames,
        @Param("genreFiltered") boolean genreFiltered,
        @Param("genreNames") List<String> genreNames,
        Pageable pageable);

    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
        LEFT JOIN FETCH m.imageFile
        WHERE m.musicId = :musicId AND m.isDeleted = false
        """)
    Optional<Music> findDetail(@Param("musicId") int musicId);

    Optional<Music> findByMusicIdAndIsDeletedFalse(int musicId);

    /**
     * <b>삭제된 곡도</b> 찾는다. 살아 있는지 아닌지는 부르는 쪽이 판단한다.
     *
     * <p>"없다"(404)와 "지워졌다"(410)를 나누려면 행 자체가 있는지 먼저 봐야 한다.
     * {@code findDetail}·{@code existsByMusicIdAndIsDeletedFalse} 는 삭제된 행을
     * 걸러서 둘 다 {@code empty}/{@code false} 가 되므로 구분되지 않는다.
     */
    Optional<Music> findByMusicId(int musicId);

    List<Music> findByMusicIdInAndIsDeletedFalse(List<Integer> musicIds);

    /** 목록 표현에 필요한 연관까지 한 번에 끌어온다. 순서는 호출 쪽이 다시 잡는다. */
    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
        LEFT JOIN FETCH m.imageFile
        WHERE m.musicId IN :musicIds AND m.isDeleted = false
        """)
    List<Music> findDetailAllByIds(@Param("musicIds") List<Integer> musicIds);

    /** 앨범의 수록곡 번호. 발매 순서를 따로 들고 있지 않아 등록 순으로 준다. */
    @Query("""
        SELECT m.musicId FROM Music m
        WHERE m.album.albumId = :albumId AND m.isDeleted = false
        ORDER BY m.musicId ASC
        """)
    List<Integer> findTrackIds(@Param("albumId") int albumId);

    /** 곡이 살아 있는지만 본다. 엔티티를 영속성 컨텍스트에 올리지 않는다. */
    boolean existsByMusicIdAndIsDeletedFalse(int musicId);

    /**
     * 살아 있는 곡 수. 취향 리포트가 대중성 백분위를 낼 때 <b>한 번도 재생되지 않은
     * 곡 수를 알아내는 데</b> 쓴다.
     *
     * 재생수 자체는 `music.play_count` 가 아니라 ClickHouse 재생 로그에서 온다 —
     * 그 컬럼을 올리는 코드가 저장소에 없어 전 곡이 0 이기 때문이다
     * (`CatalogBaseline`). 재생 기록이 없는 곡은 로그에 나타나지 않으므로, 배열에
     * 0 을 몇 개 채울지 알려면 이 수가 필요하다.
     */
    long countByIsDeletedFalse();

    /**
     * 좋아요 수는 읽고-고쳐-쓰면 동시 요청에서 하나가 사라진다. DB 에서 바로 올린다.
     *
     * `flushAutomatically` 를 켜 둔 이유: 기본값(false)이면 아직 반영되지 않은
     * 변경이 남은 채 UPDATE 가 나가고, 뒤이은 `clearAutomatically` 가 그 변경을
     * **버린다.** 반응 행 저장과 좋아요 수 갱신이 한 트랜잭션에 있으므로 반드시
     * 먼저 내보내야 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Music m SET m.likeCount = m.likeCount + 1 WHERE m.musicId = :musicId")
    void increaseLikeCount(@Param("musicId") int musicId);

    /** 0 아래로는 내려가지 않는다. 과거에 어긋난 값이 있어도 음수가 되지 않게 막는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Music m SET m.likeCount = m.likeCount - 1 WHERE m.musicId = :musicId AND m.likeCount > 0")
    void decreaseLikeCount(@Param("musicId") int musicId);

    /**
     * 한 곡의 누적 재생 수를 <b>절대값으로</b> 덮어쓴다. {@code PlayCountBatch} 전용이다.
     *
     * <h2>왜 {@code + 1} 이 아니라 대입인가</h2>
     *
     * 좋아요({@code increaseLikeCount})와 다르다. 좋아요는 사용자 행동 한 건이 곧 증가분
     * 하나라 더하는 것이 맞지만, 재생수는 <b>ClickHouse 집계가 정본</b>이다. 원천이
     * 누적값을 들고 있으므로 MySQL 은 그것을 <b>옮겨 적는 사본</b>이다.
     *
     * <p>더하는 방식이었다면 두 번 돌 때 두 배가 되고, 이벤트가 유실됐을 때 그 손실이
     * 영원히 남는다(원천에 다시 나타나도 이미 어긋난 값 위에 더해진다). 대입은 매번
     * 원천으로 되돌리므로 <b>두 번 돌려도, 하루를 건너뛰어도, 이벤트가 늦게 도착해도</b>
     * 값이 스스로 맞는다.
     *
     * <h2>값이 줄어드는 것도 정상이다</h2>
     *
     * {@code listen_events} 는 18개월 TTL 이고 {@code ReplicatedReplacingMergeTree} 라
     * 중복 제거가 나중에 일어난다. 그래서 집계값이 지난번보다 <b>작아질 수 있다.</b>
     * {@code GREATEST} 같은 것으로 큰 값을 유지하면 그 감소를 막아버려 원천과 갈린다 —
     * 여기서는 그냥 덮어쓴다.
     *
     * <h2>음수 방어만 남긴다</h2>
     *
     * 곡이 지워져도 재생 로그에는 남으므로 그럴 일은 없지만, 집계가 비정상이어도 컬럼이
     * 음수가 되지는 않게 막는다. 부르는 쪽이 이미 0 이상만 넘긴다.
     *
     * <h2>왜 등록 수정 쿼리와 달리 결과를 돌려주는가</h2>
     *
     * 갱신된 행 수를 돌려준다. 0 이면 <b>그런 곡이 없다</b>는 뜻이고(삭제된 곡의 재생
     * 로그가 남아 있는 정상적인 경우), 배치는 그것을 실패가 아니라 별도 카운터로 센다.
     * {@code increaseLikeCount} 처럼 {@code void} 로 두면 "대상이 없었다" 와 "갱신됐다" 가
     * 구분되지 않아, 곡이 지워진 뒤로는 매일 조용히 아무 일도 안 하면서 성공으로 보인다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Music m SET m.playCount = :playCount WHERE m.musicId = :musicId")
    int updatePlayCount(@Param("musicId") int musicId, @Param("playCount") int playCount);

}
