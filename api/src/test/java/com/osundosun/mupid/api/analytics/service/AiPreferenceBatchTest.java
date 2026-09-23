package com.osundosun.mupid.api.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.osundosun.mupid.api.analytics.dto.AiPreferenceHourRow;
import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;
import com.osundosun.mupid.api.analytics.repository.AiPreferenceRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

/**
 * AI 선호도 시간별 집계 배치 (S15P21A204-427). <b>인프라 없이 돈다</b> — ClickHouse 와
 * Redis 는 가짜다. 여기서 볼 것은 SQL 이 아니라 <b>창을 어떻게 자르고 무엇을 쓰는가</b>다.
 *
 * <h2>무엇을 못박는가</h2>
 *
 * <ol>
 *   <li><b>빈 칸도 쓴다</b> — 창의 모든 시간 × 모든 구분이 0 으로라도 나가야, 재계산에서
 *       사라진 칸의 옛 값이 남지 않고 화면이 "0회" 와 "계산 안 함" 을 가를 수 있다.</li>
 *   <li><b>창 끝은 제외다</b> — 창 밖의 행이 섞이면 옆 회차의 칸을 덜 센 값으로 덮는다.</li>
 *   <li><b>대입이다</b> — 같은 입력으로 두 번 돌리면 같은 행이 나간다(멱등).</li>
 *   <li><b>집계가 실패하면 아무것도 안 쓴다</b>, 그리고 선점에 지면 집계하지 않는다.</li>
 * </ol>
 */
class AiPreferenceBatchTest {

    private static final LocalDateTime TEN = LocalDateTime.of(2026, 9, 22, 10, 0);
    private static final int SEGMENTS = AiPreferenceSegment.values().length;

    private AiPreferenceRepository repository;
    private CacheService cache;
    private AiPreferenceBatch batch;

    @BeforeEach
    void setUp() {
        repository = mock(AiPreferenceRepository.class);
        cache = mock(CacheService.class);
        batch = new AiPreferenceBatch(repository, cache);
    }

    @Test
    @DisplayName("창의 모든 시간 × 모든 구분을 채운다 — 센 칸은 그 값, 나머지는 0")
    void fillsEveryHourAndSegment() {
        AiPreferenceHourRow counted = row(TEN.plusHours(1), AiPreferenceSegment.HOME, 4, 3);

        List<AiPreferenceHourRow> filled = fill(TEN, TEN.plusHours(3), List.of(counted));

        assertEquals(3 * SEGMENTS, filled.size());
        // 시간 오름차순, 그 안에서 구분 선언 순서다 — 적재가 한 덩어리로 나간다.
        assertEquals(TEN, filled.get(0).hour());
        assertEquals(AiPreferenceSegment.HOME, filled.get(0).segment());
        assertEquals(TEN.plusHours(2), filled.get(filled.size() - 1).hour());
        assertEquals(AiPreferenceSegment.NON_AI, filled.get(filled.size() - 1).segment());

        assertEquals(counted, filled.get(SEGMENTS));
        long nonZero = filled.stream().filter(r -> r.plays() > 0).count();
        assertEquals(1, nonZero);
    }

    @Test
    @DisplayName("창 밖의 행은 버린다 — 끝 시각은 제외다")
    void dropsRowsOutsideWindow() {
        List<AiPreferenceHourRow> filled = fill(TEN, TEN.plusHours(2), List.of(
            row(TEN.minusHours(1), AiPreferenceSegment.SECTION, 9, 9),
            row(TEN.plusHours(2), AiPreferenceSegment.SECTION, 7, 7)));

        assertEquals(2 * SEGMENTS, filled.size());
        assertTrue(filled.stream().allMatch(r -> r.plays() == 0));
    }

    @Test
    @DisplayName("같은 칸에 두 행이 오면 건수는 더하고 사람 수는 큰 쪽을 쓴다")
    void mergesDuplicateCell() {
        List<AiPreferenceHourRow> filled = fill(TEN, TEN.plusHours(1), List.of(
            row(TEN, AiPreferenceSegment.UNTYPED, 2, 2),
            row(TEN, AiPreferenceSegment.UNTYPED, 3, 1)));

        AiPreferenceHourRow untyped = filled.stream()
            .filter(r -> r.segment() == AiPreferenceSegment.UNTYPED).findFirst().orElseThrow();
        assertEquals(5, untyped.plays());
        // 더하면 같은 사람을 두 번 셀 수 있다.
        assertEquals(2, untyped.users());
    }

    @Test
    @DisplayName("집계는 원본을 창 끝 뒤로 TRAILING 만큼 더 읽고, 채운 행을 통째로 쓴다")
    void aggregateReadsTrailingAndWritesFilled() {
        when(repository.aggregate(TEN, TEN.plusHours(6), AiPreferenceBatch.TRAILING))
            .thenReturn(List.of(row(TEN, AiPreferenceSegment.HOME, 1, 1)));
        when(repository.replace(anyList(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        int written = batch.aggregate(TEN, TEN.plusHours(6));

        assertEquals(6 * SEGMENTS, written);
        verify(repository).aggregate(TEN, TEN.plusHours(6), AiPreferenceBatch.TRAILING);
    }

    @Test
    @DisplayName("두 번 돌리면 같은 행이 나간다 — 덧셈이 아니라 대입이다")
    void aggregateIsIdempotent() {
        when(repository.aggregate(any(), any(), any()))
            .thenReturn(List.of(row(TEN, AiPreferenceSegment.SECTION, 3, 2)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiPreferenceHourRow>> written = ArgumentCaptor.forClass(List.class);

        batch.aggregate(TEN, TEN.plusHours(2));
        batch.aggregate(TEN, TEN.plusHours(2));

        verify(repository, times(2)).replace(written.capture(), any());
        assertEquals(written.getAllValues().get(0), written.getAllValues().get(1));
    }

    @Test
    @DisplayName("집계가 실패하면 아무것도 쓰지 않는다")
    void writesNothingWhenAggregateFails() {
        when(repository.aggregate(any(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("down"));

        assertThrows(DataAccessResourceFailureException.class,
            () -> batch.aggregate(TEN, TEN.plusHours(1)));
        verify(repository, never()).replace(anyList(), any());
    }

    @Test
    @DisplayName("정시가 아닌 경계는 거절한다 — 칸이 반쪽으로 잘린다")
    void rejectsNonHourBoundary() {
        assertThrows(IllegalArgumentException.class,
            () -> batch.aggregate(TEN.plusMinutes(30), TEN.plusHours(2)));
    }

    @Test
    @DisplayName("지난 날짜의 다시 집계는 하루씩 끊어 24시간 × 구분을 쓴다")
    void backfillSplitsByDay() {
        when(repository.aggregate(any(), any(), any())).thenReturn(List.of());
        when(repository.replace(anyList(), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        int written = batch.backfill(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3));

        assertEquals(3 * 24 * SEGMENTS, written);
        verify(repository).aggregate(eq(LocalDateTime.of(2026, 9, 2, 0, 0)),
            eq(LocalDateTime.of(2026, 9, 3, 0, 0)), any(Duration.class));
        verify(repository, times(3)).aggregate(any(), any(), any());
    }

    @Test
    @DisplayName("선점에 지면 집계하지 않는다")
    void skipsWhenAnotherInstanceHoldsTheHour() {
        when(cache.setIfAbsent(anyString(), eq(CacheKind.AiPreferenceBatch), any())).thenReturn(false);

        batch.runHourly();

        verify(repository, never()).aggregate(any(), any(), any());
    }

    @Test
    @DisplayName("정기 회차는 직전 RECOMPUTE_HOURS 시간을 다시 세고, 실패해도 예외를 내지 않는다")
    void hourlyRecomputesRecentWindowAndSwallowsFailure() {
        when(cache.setIfAbsent(anyString(), eq(CacheKind.AiPreferenceBatch), any())).thenReturn(true);
        List<LocalDateTime> asked = new ArrayList<>();
        when(repository.aggregate(any(), any(), any())).thenAnswer(inv -> {
            asked.add(inv.getArgument(0));
            asked.add(inv.getArgument(1));
            throw new DataAccessResourceFailureException("down");
        });

        batch.runHourly();

        assertEquals(2, asked.size());
        assertEquals(Duration.ofHours(AiPreferenceBatch.RECOMPUTE_HOURS),
            Duration.between(asked.get(0), asked.get(1)));
        assertEquals(0, asked.get(1).getMinute());
        verify(repository, never()).replace(anyList(), any());
    }

    // ------------------------------------------------------------------ 도우미

    private static List<AiPreferenceHourRow> fill(
        LocalDateTime from, LocalDateTime to, List<AiPreferenceHourRow> found
    ) {
        return AiPreferenceBatch.fill(from, to, found);
    }

    private static AiPreferenceHourRow row(LocalDateTime hour, AiPreferenceSegment segment,
                                           long plays, long users) {
        return new AiPreferenceHourRow(hour, segment, plays, users, plays, 0, 0, 0, 0, plays * 1000);
    }

}
