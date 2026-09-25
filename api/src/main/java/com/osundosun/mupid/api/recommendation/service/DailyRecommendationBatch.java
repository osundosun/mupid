package com.osundosun.mupid.api.recommendation.service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.config.AsyncConfig;
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
 * <h2>누가 부르나 (S15P21A204-448)</h2>
 *
 * <p>먼저 매일 도는 데이터 배치(`music_preprocessing/run_batch.sh`)가 곡 등록·구간
 * 분석·색인을 마친 뒤 {@link #startToday()} 로 시작시킨다 — 그날 들어온 곡까지 후보에
 * 넣기 위해서다. 요청은 바로 돌려주고 생성은 전용 실행기에서 돈다. 정오 스케줄
 * ({@link #runToday()})은 <b>예비</b>다. 배치가 실패했거나 분석이 실패해 부르지 않은 날에만
 * 실제로 만들고, 배치가 이미 만든 회원은 건너뛴다. 두 길 모두 같은 선점을 잡는다.
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
     * 배치가 자기 선점에 막힌다. 모든 길이 끝나면 선점을 풀므로, 이 값은 도중에 죽었을 때
     * 선점이 풀리기까지의 시간이다. 추천 서버 호출은 회원당 최악 약 14초(연결 2초 + 읽기
     * 5초, 한 번 재시도)라 6시간이면 최악으로도 약 1,500명, p50(2.2초)이면 약 9,800명이다.
     */
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    private final UserRepository userRepository;
    private final DailyRecommendationService dailyRecommendationService;
    private final CacheService cacheService;
    @Qualifier(AsyncConfig.DAILY_RECOMMENDATION_EXECUTOR)
    private final TaskExecutor dailyExecutor;

    /**
     * 매일 정오. <b>예비다</b> — 보통은 데이터 배치가 {@link #startToday()} 로 먼저 만든다.
     *
     * <p>배치가 만든 날에는 모든 회원이 "이미 있음" 으로 건너뛰어 추천 서버를 부르지 않는다.
     * 배치가 실패했거나 분석이 실패해 부르지 않은 날에만 여기서 만든다.
     */
    @Scheduled(cron = "0 0 12 * * *", zone = "Asia/Seoul")
    public void runToday() {
        runClaimed(LocalDate.now(), false);
    }

    /**
     * 선점을 잡고 그 자리에서 만든다. 정오 예비와 운영자 수동 생성이 쓴다.
     *
     * <p>세 길(데이터 배치의 시작 요청·정오 예비·수동 생성)이 <b>같은 날짜 선점</b>을 잡는다.
     * 그래서 어느 둘이 겹쳐도 같은 날이 동시에 두 번 돌지 않는다. 끝나면 선점을 푼다 — 쥔 채
     * 두면 그날 추천 서버가 흔들려 몇 명이 빠졌을 때 운영자가 {@link #LOCK_TTL} 동안 다시 돌릴
     * 수 없다. TTL 은 도중에 프로세스가 죽었을 때만 쓰인다.
     *
     * @return 만든 사용자 수. 이미 누군가 돌고 있으면 비어 있다
     */
    public OptionalInt runClaimed(LocalDate date, boolean force) {
        if (!claim(date)) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(run(date, force));
        } finally {
            release(date);
        }
    }

    /**
     * 데이터 배치가 부르는 자리. 선점을 잡으면 전용 실행기에 넘기고 <b>바로</b> 돌아온다.
     *
     * <p>회원 수만큼 추천 서버를 부르는 동안 HTTP 요청을 붙잡고 있지 않는다 — 붙잡으면 nginx
     * 60초에 끊기고, 끊겨도 서버는 계속 돌아 배치만 실패로 남는다. 선점이 이미 잡혀 있으면
     * (다른 인스턴스·재시도·정오 예비가 돌고 있으면) 아무것도 하지 않는다.
     *
     * <p>끝나면 선점을 푼다. 그래야 도중에 몇 명이 실패한 날 정오 예비가 그 사람들을 채운다.
     *
     * @return 이번 호출로 시작했으면 true, 이미 누군가 돌고 있으면 false
     */
    public boolean startToday() {
        LocalDate today = LocalDate.now();
        if (!claim(today)) {
            return false;
        }
        try {
            dailyExecutor.execute(() -> {
                try {
                    run(today, false);
                } finally {
                    release(today);
                }
            });
        } catch (TaskRejectedException e) {
            // 넘기지 못했으면 선점을 쥔 채 두지 않는다 — 정오 예비까지 막힌다.
            release(today);
            throw e;
        }
        return true;
    }

    private boolean claim(LocalDate date) {
        if (cacheService.setIfAbsent(date.toString(), CacheKind.DailyRecommendationBatch, LOCK_TTL)) {
            return true;
        }
        log.info("오늘의 모먼트 배치를 다른 실행이 이미 잡았다. date={}", date);
        return false;
    }

    private void release(LocalDate date) {
        cacheService.delete(date.toString(), CacheKind.DailyRecommendationBatch);
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
