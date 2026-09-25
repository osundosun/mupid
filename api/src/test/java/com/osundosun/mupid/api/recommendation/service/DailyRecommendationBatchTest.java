package com.osundosun.mupid.api.recommendation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.repository.UserRepository;

/**
 * 오늘의 모먼트 시작 (S15P21A204-448). <b>인프라 없이 돈다</b> — Redis·DB·실행기는 가짜다.
 *
 * <p>데이터 배치의 시작 요청과 정오 예비 스케줄이 <b>같은 선점</b>을 잡는지, 시작 요청이
 * 요청 스레드에서 돌지 않는지, 선점을 쥔 채 남지 않는지를 본다.
 */
class DailyRecommendationBatchTest {

    private UserRepository users;
    private DailyRecommendationService service;
    private CacheService cache;
    private List<Runnable> queued;
    private DailyRecommendationBatch batch;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = mock(DailyRecommendationService.class);
        cache = mock(CacheService.class);
        queued = new ArrayList<>();
        TaskExecutor executor = queued::add;          // 넘겨받기만 하고 돌리지 않는다
        batch = new DailyRecommendationBatch(users, service, cache, executor);
        when(users.findAllUserIdsByStatus(UserStatus.ACTIVE)).thenReturn(List.of(1, 2));
    }

    @Test
    @DisplayName("시작 요청은 선점을 잡고 실행기에 넘긴 뒤 바로 돌아온다 — 요청 스레드에서 만들지 않는다")
    void startHandsOffAndReturns() {
        when(cache.setIfAbsent(anyString(), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(true);

        assertTrue(batch.startToday());

        verify(service, never()).generate(anyInt(), any(), anyBoolean());
        queued.get(0).run();
        verify(service).generate(1, LocalDate.now(), false);
        verify(service).generate(2, LocalDate.now(), false);
        // 끝나면 선점을 푼다 — 도중에 실패한 회원을 정오 예비가 채울 수 있게.
        verify(cache).delete(LocalDate.now().toString(), CacheKind.DailyRecommendationBatch);
    }

    @Test
    @DisplayName("이미 누가 돌고 있으면 시작하지 않는다 — 배치를 다시 보내도 같은 날이 두 번 돌지 않는다")
    void startDoesNothingWhenClaimed() {
        when(cache.setIfAbsent(anyString(), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(false);

        assertFalse(batch.startToday());
        assertTrue(queued.isEmpty());
        verify(cache, never()).delete(anyString(), any());
    }

    @Test
    @DisplayName("실행기가 거절하면 선점을 풀고 알린다 — 쥔 채 두면 정오 예비까지 막힌다")
    void rejectedHandOffReleasesTheClaim() {
        when(cache.setIfAbsent(anyString(), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(true);
        DailyRecommendationBatch full = new DailyRecommendationBatch(users, service, cache,
            task -> { throw new TaskRejectedException("full"); });

        assertThrows(TaskRejectedException.class, full::startToday);
        verify(cache).delete(LocalDate.now().toString(), CacheKind.DailyRecommendationBatch);
    }

    @Test
    @DisplayName("정오 예비는 같은 선점을 잡고, 잡혀 있으면 만들지 않는다")
    void noonBackupUsesTheSameClaim() throws Exception {
        Method noon = DailyRecommendationBatch.class.getMethod("runToday");
        assertTrue(noon.isAnnotationPresent(Scheduled.class));

        when(cache.setIfAbsent(anyString(), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(false);
        batch.runToday();
        verify(service, never()).generate(anyInt(), any(), anyBoolean());
    }

    @Test
    @DisplayName("수동 생성도 같은 선점을 잡는다 — 돌고 있으면 비어 있고, 끝나면 선점을 푼다")
    void manualGenerateUsesTheSameClaim() {
        LocalDate day = LocalDate.of(2026, 9, 25);
        when(cache.setIfAbsent(eq(day.toString()), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(false, true);
        when(service.generate(anyInt(), eq(day), eq(true))).thenReturn(true);

        assertTrue(batch.runClaimed(day, true).isEmpty());
        verify(service, never()).generate(anyInt(), any(), anyBoolean());

        assertEquals(2, batch.runClaimed(day, true).getAsInt());
        verify(cache).delete(day.toString(), CacheKind.DailyRecommendationBatch);
    }

    @Test
    @DisplayName("생성 도중 예외가 나도 선점을 푼다")
    void claimIsReleasedEvenWhenTheRunThrows() {
        LocalDate day = LocalDate.of(2026, 9, 25);
        when(cache.setIfAbsent(eq(day.toString()), eq(CacheKind.DailyRecommendationBatch), any(Duration.class)))
            .thenReturn(true);
        when(users.findAllUserIdsByStatus(UserStatus.ACTIVE)).thenThrow(new IllegalStateException("db"));

        assertThrows(IllegalStateException.class, () -> batch.runClaimed(day, false));
        verify(cache).delete(day.toString(), CacheKind.DailyRecommendationBatch);
    }

}
