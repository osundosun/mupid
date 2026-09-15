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
     * 제목·아티스트명·앨범명으로 검색한다. 검색어가 비면 전체 목록이다.
     *
     * 아티스트·앨범을 함께 가져온다. 지연 로딩에 맡기면 한 페이지(20곡)에
     * 40번의 추가 질의가 나간다.
     */
    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
        WHERE m.isDeleted = false
          AND (:keyword IS NULL
               OR LOWER(m.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(al.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<Music> search(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
        WHERE m.musicId = :musicId AND m.isDeleted = false
        """)
    Optional<Music> findDetail(@Param("musicId") int musicId);

    List<Music> findByMusicIdInAndIsDeletedFalse(List<Integer> musicIds);

    /** 목록 표현에 필요한 연관까지 한 번에 끌어온다. 순서는 호출 쪽이 다시 잡는다. */
    @Query("""
        SELECT m FROM Music m
        JOIN FETCH m.artist a
        JOIN FETCH a.imageFile
        JOIN FETCH m.album al
        JOIN FETCH al.imageFile
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

}
