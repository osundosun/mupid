package com.osundosun.mupid.api.music.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.Album;

public interface AlbumRepository extends JpaRepository<Album, Integer> {

    /**
     * 목록·검색. 앨범명과 아티스트명 둘 다 본다.
     *
     * `artistId` 를 주면 그 아티스트의 앨범만 남긴다.
     */
    @Query("""
        SELECT al FROM Album al
        JOIN FETCH al.imageFile
        JOIN FETCH al.artist a
        JOIN FETCH a.imageFile
        WHERE al.isDeleted = false
          AND (:artistId IS NULL OR a.artistId = :artistId)
          AND (:keyword IS NULL
               OR LOWER(al.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<Album> search(
        @Param("keyword") String keyword,
        @Param("artistId") Integer artistId,
        Pageable pageable);

    @Query("""
        SELECT al FROM Album al
        JOIN FETCH al.imageFile
        JOIN FETCH al.artist a
        JOIN FETCH a.imageFile
        WHERE al.albumId = :albumId AND al.isDeleted = false
        """)
    Optional<Album> findDetail(@Param("albumId") int albumId);

}
