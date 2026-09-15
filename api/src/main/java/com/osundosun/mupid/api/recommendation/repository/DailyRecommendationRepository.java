package com.osundosun.mupid.api.recommendation.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;

public interface DailyRecommendationRepository extends JpaRepository<DailyRecommendation, Integer> {

    /** 그날의 추천 묶음. 분위기 이름까지 필요해서 코드도 함께 끌어온다. */
    @Query("""
        SELECT r FROM DailyRecommendation r
        JOIN FETCH r.moodCode
        WHERE r.user.userId = :userId AND r.recommendationDate = :date
        ORDER BY r.recommendationId ASC
        """)
    List<DailyRecommendation> findByUserAndDate(
        @Param("userId") Integer userId,
        @Param("date") LocalDate date);

    /**
     * 가장 최근 추천이 있는 날.
     *
     * 파이프라인이 매일 돌지 않을 수 있어서, 오늘 것이 없으면 이 날짜로 되돌아간다.
     */
    @Query("""
        SELECT MAX(r.recommendationDate) FROM DailyRecommendation r
        WHERE r.user.userId = :userId
        """)
    LocalDate findLatestDate(@Param("userId") Integer userId);

}
