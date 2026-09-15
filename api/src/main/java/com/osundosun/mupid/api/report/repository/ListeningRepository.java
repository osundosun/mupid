package com.osundosun.mupid.api.report.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.report.entity.Listening;

public interface ListeningRepository extends JpaRepository<Listening, Listening.ListeningId> {

    /** 저장된 리포트의 곡별 청취량. 많이 들은 순. */
    @Query("""
        SELECT l FROM Listening l
        WHERE l.report.reportId = :reportId
        ORDER BY l.listeningMs DESC
        """)
    List<Listening> findByReportId(@Param("reportId") int reportId);

    /**
     * 그 리포트의 곡별 청취량을 전부 지운다. 스냅샷은 갱신이 아니라 **갈아 끼우기**다.
     *
     * 하루가 진행되는 동안 안 듣게 된 곡이 생기지는 않지만, 곡 목록이 줄어드는
     * 경우(곡 삭제)에 옛 행이 남으면 합이 어긋난다.
     */
    void deleteByReport_ReportId(int reportId);

}
