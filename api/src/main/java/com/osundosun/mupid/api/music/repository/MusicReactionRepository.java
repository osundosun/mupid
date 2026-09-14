package com.osundosun.mupid.api.music.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.MusicReaction;
import com.osundosun.mupid.api.music.enumerate.ReactionType;

public interface MusicReactionRepository extends JpaRepository<MusicReaction, MusicReaction.MusicReactionId> {

    Optional<MusicReaction> findByMusicReactionId_UserIdAndMusicReactionId_MusicId(Integer userId, Integer musicId);

    /**
     * 이 사람이 반응한 곡 번호.
     *
     * 화면이 하트 상태를 그리려면 목록 전체가 필요하다. 번호만이라 페이징 없이
     * 한 번에 준다 — 곡 수천 개를 좋아해도 int 배열이라 가볍다.
     */
    @Query("""
        SELECT r.musicReactionId.musicId FROM MusicReaction r
        WHERE r.musicReactionId.userId = :userId AND r.reactionType = :type
        ORDER BY r.musicReactionId.musicId DESC
        """)
    List<Integer> findMusicIds(@Param("userId") Integer userId, @Param("type") ReactionType type);

    /** 라이브러리 화면용. 곡 정보를 붙여야 하므로 여기서는 번호만 페이지로 끊는다. */
    @Query("""
        SELECT r.musicReactionId.musicId FROM MusicReaction r
        WHERE r.musicReactionId.userId = :userId AND r.reactionType = :type
        ORDER BY r.updatedAt DESC, r.musicReactionId.musicId DESC
        """)
    Page<Integer> findMusicIdPage(
        @Param("userId") Integer userId,
        @Param("type") ReactionType type,
        Pageable pageable
    );

}
