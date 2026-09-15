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
import com.osundosun.mupid.api.report.response.ReportResponse;
import com.osundosun.mupid.api.report.service.ReportService;
import com.osundosun.mupid.api.report.service.ReportServiceImpl;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * '내 취향' 리포트.
 *
 * 하루 단위만 있다. 주·월은 지금 화면에 없고, 계약을 미리 열어 두면 채우지 않은
 * 파라미터가 굳는다.
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

}
