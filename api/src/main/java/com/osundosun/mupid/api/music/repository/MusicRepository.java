package com.osundosun.mupid.api.music.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

}
