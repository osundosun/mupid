package com.osundosun.mupid.api.music.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.MusicComment;

public interface MusicCommentRepository extends JpaRepository<MusicComment, Integer> {

    /**
     * 한 곡의 댓글. 시점 오름차순 — 화면이 파형 위에 늘어놓기 때문이다.
     *
     * 작성자를 함께 끌어온다. 목록마다 닉네임을 따로 읽으면 N+1 이다.
     */
    @Query("""
        SELECT c FROM MusicComment c
        JOIN FETCH c.user
        WHERE c.music.musicId = :musicId AND c.isDeleted = false
        ORDER BY c.timestampMs ASC, c.musicCommentId ASC
        """)
    Page<MusicComment> findByMusic(@Param("musicId") int musicId, Pageable pageable);

    Optional<MusicComment> findByMusicCommentIdAndIsDeletedFalse(Integer musicCommentId);

    /**
     * <b>삭제된 댓글도</b> 찾는다. 살아 있는지 아닌지는 부르는 쪽이 판단한다.
     *
     * <p>"없다"(404)와 "지워졌다"(410)를 나누려면 행 자체가 있는지 먼저 봐야 한다.
     * 위 `…AndIsDeletedFalse` 는 삭제된 댓글을 걸러서 둘 다 {@code empty} 다.
     */
    Optional<MusicComment> findByMusicCommentId(Integer musicCommentId);

}
