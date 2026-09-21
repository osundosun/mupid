package com.osundosun.mupid.api.report.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.report.dto.ReportRange;
import com.osundosun.mupid.api.report.response.ReportDailyRangeResponse;
import com.osundosun.mupid.api.report.response.ReportResponse;
import com.osundosun.mupid.api.report.service.ReportService;
import com.osundosun.mupid.api.report.service.ReportServiceImpl;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * '내 취향' 리포트.
 *
 * 두 갈래다 — 하루 한 장(`GET /api/v1/report`)과 여러 날을 한 번에
 * (`GET /api/v1/report/daily`). 둘은 같은 계산을 탄다.
 *
 * 주·월 **집계** 리포트는 지금 화면에 없고, 계약을 미리 열어 두면 채우지 않은
 * 파라미터가 굳는다. 여기서 늘어난 것은 기간이 아니라 **이미 있던 일별 리포트를
 * 여러 날 묶어 주는 길**이다.
 */
@RestController
@RequestMapping("/api/v1/report")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    /**
     * 하루치 리포트. `date` 를 생략하면 `Asia/Seoul` 기준 오늘이다.
     *
     * 오늘치는 조회할 때마다 ClickHouse 에서 다시 집계한다. 다만 방금 들은 곡이
     * 반영되기까지 평균 20초쯤 걸리므로(하트비트 + 프론트 큐 + Kafka flush) 화면은
     * 폴링으로 그 지연을 덮는다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<ReportResponse>> getDailyReport(
        Authentication auth,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        User user = (User)auth.getPrincipal();
        LocalDate target = date == null ? LocalDate.now(ReportServiceImpl.ZONE) : date;
        return ResponseEntity.ok(BaseResponse.of(
            reportService.getDailyReport(user.getUserId(), target)
        ));
    }

    /**
     * 여러 날치 리포트. 주간 달력이 쓴다.
     *
     * <p>`from` 과 `to` 는 <b>둘 다 필수이고 양끝을 포함한다.</b> 날짜의 기준 시간대는
     * `Asia/Seoul` 이며 응답의 `time_zone` 이 그 값을 그대로 말한다.
     *
     * <h2>왜 날짜별 조회를 묶었나</h2>
     *
     * 화면이 일곱 날을 그리는데 그때까지는 요청을 날마다 따로 보냈다
     * (프론트의 `CONCURRENCY = 3`). 스냅샷이 없는 지난 날짜는 조회할 때 계산해
     * 저장하므로(backfill-on-read, decision/0019) 창을 열 때마다 ClickHouse 집계가
     * 날 수만큼 돌았다. 한 요청으로 묶으면 클라이언트 왕복이 한 번이 된다.
     *
     * <h2>요청 날짜가 빠지지 않는다</h2>
     *
     * 기록이 없는 날도 `days` 에 들어가고 `total_listening_ms` 가 0 이다. 화면이
     * 카드 일곱 장을 항상 그리므로 빼면 "기록 없음"과 "로딩 중"을 구분할 수 없다.
     *
     * @param from 시작일(`YYYY-MM-DD`). 포함
     * @param to   종료일(`YYYY-MM-DD`). 포함
     * @return 400 `MUPID_REPORT_INVALID_DATE` — 값이 없거나 `from > to` 이거나 `to` 가
     *         미래일 때. 400 `MUPID_REPORT_RANGE_TOO_LONG` — 창이 31일을 넘을 때.
     *         503 `MUPID_REPORT_ANALYTICS_UNAVAILABLE` — ClickHouse 를 못 읽을 때
     */
    @GetMapping("/daily")
    public ResponseEntity<BaseResponse<ReportDailyRangeResponse>> getDailyReportRange(
        Authentication auth,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        User user = (User)auth.getPrincipal();
        ReportRange range = ReportRange.of(from, to, LocalDate.now(ReportServiceImpl.ZONE));
        return ResponseEntity.ok(BaseResponse.of(
            reportService.getDailyReportRange(user.getUserId(), range)
        ));
    }

}
