package com.osundosun.mupid.api.recommendation.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 「오늘의 모먼트」를 매일 한 번 만들어 둔다.
 *
 * <h2>왜 미리 만드나</h2>
 *
 * <p>화면이 열릴 때마다 추천 서버를 부르면 같은 하루에 다른 곡이 나온다. "오늘의" 라는
 * 말이 성립하려면 하루 동안 같아야 하고, 그러려면 한 번 만들어 저장해야 한다. 덤으로
 * 홈 화면이 추천 서버 응답(이력이 있는 사용자는 p50 2.2초)을 기다리지 않는다.
 *
 * <h2>인스턴스마다 돈다</h2>
 *
 * <p>스케줄러는 API 인스턴스 <b>마다</b> 돌기 때문에, 여러 벌 띄우면 같은 하루를 인스턴스
 * 수만큼 만든다. `uq_daily_recommendation_user_date` 덕분에 중복 행은 안 생기지만
 * 추천 서버를 회원 수만큼 여러 번 때린다. 그래서 진입에 Redis 선점을 건다 —
 * {@code ReportDailyBatch} 와 같은 이유이고 같은 방식이다.
 *
 * <h2>실패를 어떻게 다루나</h2>
 *
 * <p><b>한 사용자가 실패해도 다음 사용자로 넘어간다.</b> 추천 서버가 잠깐 흔들렸다고
 * 그날 전체가 비면 다음 날까지 아무도 오늘의 모먼트를 못 본다. 대신 몇 명이 실패했는지
 * 로그에 남긴다 — 조용히 넘어가면 "오늘은 추천이 적네" 로만 보인다.
 *
 * <p>이미 만들어 둔 날짜는 <b>다시 만들지 않는다.</b> 배치를 두 번 돌려도 곡이 바뀌지
 * 않는다는 뜻이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyRecommendationBatch {

    /**
     * 선점 유지 시간.
     *
     * <p>배치가 도는 시간보다 넉넉하되 하루보다는 짧아야 한다. 하루를 넘기면 다음 날
     * 배치가 자기 선점에 막힌다.
     */
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    private final UserRepository userRepository;
    private final DailyRecommendationService dailyRecommendationService;
    private final CacheService cacheService;

    /**
     * 매일 정오.
     *
     * <p>자정이 아닌 이유는 아침에 한 번 듣고 난 뒤의 행동까지 반영하기 위해서다.
     * 자정에 돌면 전날 밤 이후의 신호가 통째로 빠진 채 하루치 추천이 굳는다.
     */
    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public void runToday() {
        LocalDate today = LocalDate.now();
        if (!cacheService.setIfAbsent(today.toString(),
                CacheKind.DailyRecommendationBatch, LOCK_TTL)) {
            log.info("오늘의 모먼트 배치를 다른 인스턴스가 이미 잡았다. date={}", today);
            return;
        }
        run(today, false);
    }

    /**
     * 그날치를 만든다. 만들어진 사용자 수를 돌려준다.
     *
     * @param force 이미 있어도 다시 만든다
     */
    public int run(LocalDate date, boolean force) {
        List<Integer> userIds = userRepository.findAllUserIdsByStatus(UserStatus.ACTIVE);
        if (userIds.isEmpty()) {
            log.info("오늘의 모먼트 대상 사용자가 없다 date={}", date);
            return 0;
        }

        int made = 0;
        int skipped = 0;
        int failed = 0;
        for (Integer userId : userIds) {
            try {
                if (dailyRecommendationService.generate(userId, date, force)) {
                    made++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                // 한 사람 때문에 나머지를 버리지 않는다.
                failed++;
                log.warn("오늘의 모먼트 생성 실패 user_id={} date={}", userId, date, e);
            }
        }
        log.info("오늘의 모먼트 date={} 생성={} 이미있음={} 실패={} (대상 {})",
            date, made, skipped, failed, userIds.size());
        return made;
    }

}
