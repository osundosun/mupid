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

    /**
     * 제출했는가.
     *
     * 진입 분기(`GET /api/v1/user`)와 재제출 차단이 쓴다. 둘 다 내용이 아니라
     * 있고 없고만 보므로 줄을 끌어오지 않는다.
     */
    boolean existsByUser_UserId(Integer userId);

}
