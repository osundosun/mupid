package com.osundosun.mupid.api.report.service;

import java.time.LocalDate;

import com.osundosun.mupid.api.report.response.ReportResponse;

public interface ReportService {

    /**
     * 하루치 취향 리포트.
     *
     * 오늘이면 ClickHouse 에서 그 자리에서 집계하고(`LIVE`), 지난 날짜면 저장된
     * 스냅샷을 읽는다(`STORED`). 스냅샷이 없는 지난 날짜는 그때 계산해 저장한다.
     */
    ReportResponse getDailyReport(int userId, LocalDate date);

    /**
     * 그날 리포트를 계산해 MySQL 에 확정 저장한다. 자정 배치가 부른다.
     *
     * 표본이 모자라 유형을 못 정하면 저장하지 않고 `false` 를 돌려준다 —
     * `reports.mubti_id` 가 NOT NULL 이라 애초에 쓸 수 없다.
     */
    boolean storeDailyReport(int userId, LocalDate date);

}
