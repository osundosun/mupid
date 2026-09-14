package com.osundosun.mupid.api.onboarding.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.onboarding.entity.OnboardingSurvey;

public interface OnboardingSurveyRepository extends JpaRepository<OnboardingSurvey, Integer> {

    /** 코드까지 한 번에 끌어온다. 응답이 사람이 고른 항목 수(최대 수십 줄)라 페이징은 두지 않았다. */
    @Query("""
        SELECT s FROM OnboardingSurvey s
        JOIN FETCH s.moodCode
        JOIN FETCH s.genreCode
        WHERE s.user.userId = :userId
        ORDER BY s.answerId ASC
        """)
    List<OnboardingSurvey> findAllByUser(@Param("userId") Integer userId);

    /** 다시 제출하면 이전 응답은 통째로 갈아 끼운다. */
    void deleteByUser_UserId(Integer userId);

}
