package com.osundosun.mupid.api.music.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.Artist;

public interface ArtistRepository extends JpaRepository<Artist, Integer> {

    /** 목록·검색. 이미지까지 끌어와야 표지가 뜬다. */
    @Query("""
        SELECT a FROM Artist a
        JOIN FETCH a.imageFile
        WHERE a.isDeleted = false
          AND (:keyword IS NULL OR LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
        """)
    Page<Artist> search(@Param("keyword") String keyword, Pageable pageable);

    @Query("""
        SELECT a FROM Artist a
        JOIN FETCH a.imageFile
        WHERE a.artistId = :artistId AND a.isDeleted = false
        """)
    Optional<Artist> findDetail(@Param("artistId") int artistId);

    @Query("""
        SELECT a FROM Artist a
        JOIN FETCH a.imageFile
        WHERE a.artistId IN :artistIds AND a.isDeleted = false
        """)
    List<Artist> findDetailAllByIds(@Param("artistIds") List<Integer> artistIds);

}
