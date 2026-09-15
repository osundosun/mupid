package com.osundosun.mupid.api.report.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.repository.ListenAnalyticsRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 전날 취향 리포트를 확정 저장한다.
 *
 * <h2>왜 배치가 필요한가</h2>
 *
 * 조회할 때 없으면 그 자리에서 계산해 저장하므로(backfill-on-read) 배치가 없어도
 * 데이터는 결국 채워진다 — <b>누가 그 날짜를 열어보는 순간에.</b> 배치는 "아무도 안
 * 열어본 날" 을 위한 것이고, 나중에 통계를 내거나 주·월 리포트를 붙일 때 구멍 없는
 * 일별 데이터가 필요해진다.
 *
 * <h2>이 저장소의 첫 스케줄러다</h2>
 *
 * `@EnableScheduling` 을 `ApiApplication` 에 붙이면서 들어왔다. 스케줄러는 인스턴스
 * <b>마다</b> 돌기 때문에, API 를 여러 벌 띄우면 같은 하루를 인스턴스 수만큼 계산한다.
 * `uq_report_period` 덕분에 중복 행은 안 생기지만 ClickHouse 를 쓸데없이 여러 번
 * 때린다. 그래서 진입에 Redis 선점(SETNX)을 건다. 지금 배포가 한 벌이어도 그렇게
 * 둔다 — 인스턴스가 늘어나는 순간 조용히 깨지는 종류의 문제다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportDailyBatch {

    /**
     * 선점 유지 시간.
     *
     * 배치가 도는 시간보다 넉넉하되 하루보다는 짧아야 한다. 하루를 넘기면 다음 날
     * 배치가 자기 선점에 막힌다.
     */
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    private final ListenAnalyticsRepository analytics;
    private final ReportService reportService;
    private final CacheService cacheService;

    /**
     * 매일 00:05 KST. 자정 정각이 아닌 이유는 `kafka_flush_interval_ms`(15초) 때문이다.
     * 23:59:59 에 들은 이벤트가 ClickHouse 에 닿기 전에 집계하면 그만큼 빠진다.
     */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    public void storeYesterday() {
        LocalDate yesterday = LocalDate.now(ReportServiceImpl.ZONE).minusDays(1);
        if (!cacheService.setIfAbsent(yesterday.toString(), CacheKind.ReportBatch, LOCK_TTL)) {
            log.info("전날 리포트 배치를 다른 인스턴스가 이미 잡았다. date={}", yesterday);
            return;
        }

        List<Integer> users;
        try {
            // 전체 회원이 아니라 **그날 들은 사람만**. 안 들은 사용자까지 훑으면
            // 회원 수만큼 ClickHouse 를 때리고 결과는 전부 빈 리포트다.
            users = analytics.activeUsers(yesterday);
        } catch (RuntimeException e) {
            log.warn("전날 리포트 대상 조회 실패. date={}", yesterday, e);
            return;
        }

        int stored = 0;
        int skipped = 0;
        for (Integer userId : users) {
            try {
                if (reportService.storeDailyReport(userId, yesterday)) {
                    stored++;
                } else {
                    // 표본이 모자라 유형을 못 정했다. `reports.mubti_id` 가 NOT NULL
                    // 이라 애초에 쓸 수 없다.
                    skipped++;
                }
            } catch (RuntimeException e) {
                // 한 사람이 실패해도 나머지는 계속한다.
                log.warn("전날 리포트 저장 실패 user_id={} date={}", userId, yesterday, e);
            }
        }
        log.info("전날 리포트 배치 완료. date={} 대상={} 저장={} 표본부족={}",
            yesterday, users.size(), stored, skipped);
    }

}
