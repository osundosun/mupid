package com.osundosun.mupid.api.report.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.report.entity.Report;

public interface ReportRepository extends JpaRepository<Report, Integer> {

    /**
     * 그 사용자의 그날 리포트. `uq_report_period (user_id, start_date)` 로 하루 한 행이다.
     *
     * 유형(`mubti`)을 함께 끌어온다. 리포트를 읽는 목적이 곧 유형을 보는 것이라
     * 지연 로딩에 맡길 이유가 없다.
     */
    @Query("""
        SELECT r FROM Report r
        JOIN FETCH r.mubti
        WHERE r.user.userId = :userId AND r.startDate = :startDate
        """)
    Optional<Report> findByUserAndStartDate(
        @Param("userId") int userId,
        @Param("startDate") LocalDateTime startDate
    );

}
