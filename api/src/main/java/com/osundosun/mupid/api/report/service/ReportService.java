package com.osundosun.mupid.api.report.service;

import java.time.LocalDate;

import com.osundosun.mupid.api.report.dto.ReportRange;
import com.osundosun.mupid.api.report.response.ReportDailyRangeResponse;
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
     * 여러 날의 리포트를 한 번에. 주간 달력이 쓴다.
     *
     * <p>하루치({@link #getDailyReport})와 <b>같은 계산</b>을 날마다 한 번씩 돌린다.
     * 응답이 가벼운 이유는 계산이 가벼워서가 아니라 달력이 읽는 필드만 담기 때문이다 —
     * 그래서 값은 본문 화면과 언제나 같다.
     *
     * <p>날짜마다 따로 부르면 클라이언트 왕복이 날 수만큼 늘고, 스냅샷이 없는 지난
     * 날짜는 그때마다 ClickHouse 를 다시 때린다. 한 요청으로 묶으면 그 왕복이 한 번이
     * 된다.
     *
     * @param range 검증된 기간. {@code ReportRange.of} 로만 만든다.
     */
    ReportDailyRangeResponse getDailyReportRange(int userId, ReportRange range);

    /**
     * 그날 리포트를 계산해 MySQL 에 확정 저장한다. 자정 배치가 부른다.
     *
     * 표본이 모자라 유형을 못 정하면 저장하지 않고 `false` 를 돌려준다 —
     * `reports.mubti_id` 가 NOT NULL 이라 애초에 쓸 수 없다.
     */
    boolean storeDailyReport(int userId, LocalDate date);

}
