package com.osundosun.mupid.api.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;

public interface DailyRecommendationTrackRepository extends JpaRepository<
    DailyRecommendationTrack, DailyRecommendationTrack.DailyRecommendationTrackId
> {

    /** 묶음 여러 개의 수록곡을 한 번에. 묶음마다 조회하면 N+1 이다. */
    @Query("""
        SELECT t FROM DailyRecommendationTrack t
        WHERE t.dailyRecommendationTrackId.dailyRecommendationId IN :recommendationIds
        ORDER BY t.dailyRecommendationTrackId.rankOrder ASC
        """)
    List<DailyRecommendationTrack> findByRecommendationIds(
        @Param("recommendationIds") List<Integer> recommendationIds);

    /** 묶음 하나의 곡을 통째로 지운다. 다시 만들 때 옛 곡이 섞이면 안 된다. */
    void deleteByDailyRecommendationTrackId_DailyRecommendationId(Integer recommendationId);

}
