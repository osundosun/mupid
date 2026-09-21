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

    /**
     * <b>삭제된 앨범도</b> 찾는다. 살아 있는지 아닌지는 부르는 쪽이 판단한다.
     *
     * <p>"없다"(404)와 "지워졌다"(410)를 나누려면 행 자체가 있는지 먼저 봐야 한다.
     * {@code findDetail} 은 {@code isDeleted = false} 로 걸러서 둘 다
     * {@code empty} 가 되므로 구분되지 않는다.
     */
    Optional<Album> findByAlbumId(int albumId);

}
