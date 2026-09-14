package com.osundosun.mupid.api.history.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.history.entity.PlayHistory;

public interface PlayHistoryRepository extends JpaRepository<PlayHistory, Long> {

    /**
     * 최근 들은 곡 번호. **같은 곡은 한 번만** 나온다.
     *
     * 기록은 들을 때마다 쌓이지만 화면의 "최근 감상" 은 같은 곡이 줄줄이 나오면
     * 쓸모가 없다. 곡별 마지막 재생 시각으로 묶어 최신순으로 준다.
     */
    @Query(value = """
        SELECT h.music.musicId FROM PlayHistory h
        WHERE h.user.userId = :userId AND h.music.isDeleted = false
        GROUP BY h.music.musicId
        ORDER BY MAX(h.createdAt) DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT h.music.musicId) FROM PlayHistory h
        WHERE h.user.userId = :userId AND h.music.isDeleted = false
        """)
    Page<Integer> findRecentMusicIds(@Param("userId") Integer userId, Pageable pageable);

}
