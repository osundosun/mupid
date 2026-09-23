package com.osundosun.mupid.api.analytics.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.dto.AiPreferenceHourRow;
import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;
import com.osundosun.mupid.api.analytics.repository.AiPreferenceRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 추천곡이 실제로 들리는가를 <b>한 시간에 한 번</b> 집계해 둔다 (S15P21A204-427).
 *
 * <h2>왜 미리 집계하나</h2>
 *
 * <p>관리자 화면은 이레치(168칸)를 한 번에 그린다. 화면을 열 때마다 원본을 재생 단위로
 * 접으면 이레치 하트비트를 매번 훑는다. 한 시간 칸의 값은 그 시간이 지나고 나면 거의 바뀌지
 * 않으므로 한 번 계산해 두고 읽기만 한다 — {@code PlayCountBatch} 가 재생수를 미리 채우는
 * 것과 같은 판단이다.
 *
 * <h2>멱등 — 덧셈이 아니라 대입이다</h2>
 *
 * <p>매 회차가 직전 {@link #RECOMPUTE_HOURS} 시간을 <b>원본에서 통째로 다시 세어</b>
 * 덮어쓴다. 결과 표가 ReplacingMergeTree 라 같은 (시간, 구분) 칸은 마지막에 쓴 것 하나만
 * 남는다. 그래서
 *
 * <ul>
 *   <li>같은 회차를 두 번 돌려도, 두 인스턴스가 동시에 돌아도 값이 같다.</li>
 *   <li>늦게 도착한 이벤트(Kafka → ClickHouse 는 15초까지 모아서 쓴다)나 시간을 넘겨
 *       이어진 재생의 꼬리는 다음 회차들이 주워 담는다.</li>
 *   <li>서버가 몇 시간 내려가 있어도 {@link #RECOMPUTE_HOURS} 안이면 저절로 메워진다.
 *       그보다 길면 관리자 API 로 {@link #backfill} 한다.</li>
 * </ul>
 *
 * <p>증분을 더하지 않는 이유는 {@code PlayCountBatch#refreshPlayCounts} 주석과 같다 —
 * 늦은 도착·병합 전 중복·TTL 때문에 "어디까지 더했는가" 를 상태로 들고 있어야 하고, 그
 * 상태를 잃으면 값이 조용히 어긋난다.
 *
 * <h2>빈 칸도 쓴다</h2>
 *
 * <p>재생이 없던 시간·구분도 0 으로 넣는다({@link #fill}). 안 넣으면 한 번 값이 있던 칸이
 * 재계산에서 사라졌을 때 옛 값이 그대로 남고, 화면은 "재생 0회" 와 "아직 계산 안 한 시간"
 * 을 가르지 못한다.
 *
 * <h2>인스턴스마다 돈다</h2>
 *
 * <p>다른 배치들과 같이 Redis 선점을 건다. 선점이 뚫려도 값은 틀리지 않지만(위의 멱등),
 * 같은 집계로 ClickHouse 를 인스턴스 수만큼 때릴 이유가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPreferenceBatch {

    /**
     * 한 회차가 다시 세는 시간 수.
     *
     * <p>한 시간만 세면 시간을 넘겨 이어진 재생의 꼬리와 늦게 도착한 이벤트를 영영 놓친다.
     * 여섯 시간이면 대부분의 청취 세션이 끝나 있고, 서버 재배포·장애로 회차를 몇 번
     * 걸러도 메워진다. 비용은 열두 시간치 원본을 훑는 것이라 문제가 되지 않는다.
     */
    public static final int RECOMPUTE_HOURS = 6;

    /**
     * 창의 끝 뒤로 이벤트를 더 읽는 시간. 재생 하나가 이보다 길게 이어지면 꼬리가 잘린다.
     *
     * <p>한 곡을 여섯 시간 동안 틀어 두는 일은 사실상 없다. 일시정지한 채 두고 간 재생은
     * 잘려도 된다 — 그것은 "들은 것" 이 아니다.
     */
    public static final Duration TRAILING = Duration.ofHours(6);

    /** 선점 유지 시간. 다음 회차(한 시간 뒤)가 자기 선점에 막히지 않게 한 시간보다 짧다. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(50);

    private final AiPreferenceRepository repository;
    private final CacheService cacheService;

    /**
     * 매시 5분 KST.
     *
     * <p>정각이 아닌 이유는 {@code ReportDailyBatch} 와 같다 — {@code kafka_flush_interval_ms}
     * (15초) 때문에 59분 59초의 이벤트가 ClickHouse 에 닿기 전에 세면 그만큼 빠진다.
     * 자정 배치들(00:05·00:10)과 같은 분에 겹치지만 이쪽은 질의 하나라 서로를 붙들지 않는다.
     */
    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")
    public void runHourly() {
        LocalDateTime to = LocalDateTime.now(AiPreferenceRepository.ZONE).truncatedTo(ChronoUnit.HOURS);
        if (!cacheService.setIfAbsent(to.toString(), CacheKind.AiPreferenceBatch, LOCK_TTL)) {
            log.info("AI 선호도 집계를 다른 인스턴스가 이미 잡았다. hour={}", to);
            return;
        }
        try {
            int written = aggregate(to.minusHours(RECOMPUTE_HOURS), to);
            log.info("AI 선호도 집계 완료. [{}, {}) 칸={}", to.minusHours(RECOMPUTE_HOURS), to, written);
        } catch (DataAccessException e) {
            // 아무것도 안 쓰고 끝낸다. 다음 회차가 이 창을 다시 센다(RECOMPUTE_HOURS).
            log.warn("AI 선호도 집계를 하지 못해 이번 회차를 건너뛴다. hour={}", to, e);
        }
    }

    /**
     * {@code [from, to)} 를 원본에서 다시 세어 덮어쓴다. 쓴 칸 수(시간 × 구분)를 돌려준다.
     *
     * <p>집계가 실패하면 <b>아무것도 쓰지 않는다</b> — 반쪽만 쓰면 어느 칸이 새 값인지 알 수
     * 없다. 예외는 호출부로 올린다.
     *
     * @param from 정시
     * @param to   정시, 제외
     */
    public int aggregate(LocalDateTime from, LocalDateTime to) {
        requireHour(from);
        requireHour(to);
        if (!from.isBefore(to)) {
            return 0;
        }
        List<AiPreferenceHourRow> found = repository.aggregate(from, to, TRAILING);
        List<AiPreferenceHourRow> filled = fill(from, to, found);
        return repository.replace(filled, LocalDateTime.now(AiPreferenceRepository.ZONE));
    }

    /**
     * 날짜 창을 하루씩 끊어 다시 센다. 관리자가 표를 처음 만든 뒤나 긴 장애 뒤에 부른다.
     *
     * <p>하루씩 끊는 것은 한 질의가 붙드는 원본의 양을 하루치로 묶으려는 것이다. 몇 번을
     * 불러도 같은 값이 된다({@link #aggregate} 가 대입이므로).
     *
     * @param from 시작일. 포함
     * @param to   종료일. 포함. 오늘이면 <b>지금 시간 직전까지만</b> 센다 — 아직 끝나지 않은
     *             시간을 0 에 가까운 값으로 굳혀 두면 다음 회차가 덮기 전까지 화면이 거짓말을 한다
     * @return 쓴 칸 수
     */
    public int backfill(LocalDate from, LocalDate to) {
        LocalDateTime limit = LocalDateTime.now(AiPreferenceRepository.ZONE).truncatedTo(ChronoUnit.HOURS);
        int written = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.plusDays(1).atStartOfDay();
            if (end.isAfter(limit)) {
                end = limit;
            }
            written += aggregate(start, end);
        }
        log.info("AI 선호도 다시 집계. {} ~ {} 칸={}", from, to, written);
        return written;
    }

    /**
     * 창의 <b>모든 시간 × 모든 구분</b>을 늘어놓고, 센 값이 있으면 그것으로, 없으면 0 으로
     * 채운다. 창 밖의 행은 버린다.
     *
     * <p>순수 함수라 따로 떼어 두었다 — 경계(창 끝 제외, 빈 칸, 모르는 구분)를 ClickHouse
     * 없이 검사하려고.
     */
    static List<AiPreferenceHourRow> fill(
        LocalDateTime from,
        LocalDateTime to,
        List<AiPreferenceHourRow> found
    ) {
        Map<LocalDateTime, Map<AiPreferenceSegment, AiPreferenceHourRow>> byHour = new HashMap<>();
        for (AiPreferenceHourRow row : found) {
            byHour.computeIfAbsent(row.hour(), hour -> new EnumMap<>(AiPreferenceSegment.class))
                .merge(row.segment(), row, AiPreferenceBatch::sum);
        }

        List<AiPreferenceHourRow> rows = new ArrayList<>();
        for (LocalDateTime hour = from; hour.isBefore(to); hour = hour.plusHours(1)) {
            Map<AiPreferenceSegment, AiPreferenceHourRow> segments = byHour.getOrDefault(hour, Map.of());
            for (AiPreferenceSegment segment : AiPreferenceSegment.values()) {
                AiPreferenceHourRow row = segments.get(segment);
                rows.add(row == null ? AiPreferenceHourRow.empty(hour, segment) : row);
            }
        }
        return rows;
    }

    /**
     * 같은 칸에 행이 둘 온 경우 — 모르는 {@code rec_type} 여러 개가 {@code UNTYPED} 하나로
     * 되읽혔을 때다. SQL 이 이미 모아 주므로 평소에는 일어나지 않는다.
     *
     * <p>{@code users} 는 더하면 겹친 사람을 두 번 센다. 정확한 값을 알 수 없으니 큰 쪽을 쓴다
     * — 적어도 그만큼은 있었다.
     */
    private static AiPreferenceHourRow sum(AiPreferenceHourRow a, AiPreferenceHourRow b) {
        return new AiPreferenceHourRow(
            a.hour(), a.segment(),
            a.plays() + b.plays(),
            Math.max(a.users(), b.users()),
            a.engagedPlays() + b.engagedPlays(),
            a.completedPlays() + b.completedPlays(),
            a.skippedPlays() + b.skippedPlays(),
            a.earlySkipPlays() + b.earlySkipPlays(),
            a.likedPlays() + b.likedPlays(),
            a.listenedMs() + b.listenedMs());
    }

    private static void requireHour(LocalDateTime time) {
        if (!time.equals(time.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("정시가 아니다: " + time);
        }
    }

}
