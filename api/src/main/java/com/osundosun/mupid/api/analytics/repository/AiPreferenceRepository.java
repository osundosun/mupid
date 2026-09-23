package com.osundosun.mupid.api.analytics.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.AiPreferenceHourRow;
import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;

/**
 * AI 추천곡이 실제로 들리는가 — 시간별 집계를 계산하고, 쌓고, 읽는다 (S15P21A204-427).
 *
 * <h2>계산은 원본에서, 읽기는 집계 표에서</h2>
 *
 * {@link #aggregate} 는 {@code listen_events} 원본을 재생 단위로 접어 시간 × 구분으로
 * 센다. 배치만 부른다. 관리자 화면은 {@link #read} 로 그 결과만 읽는다 — 이레치 원본을
 * 화면을 열 때마다 훑지 않는다.
 *
 * <h2>원본을 읽을 때의 세 규칙</h2>
 *
 * {@link UserActivityRepository} 머리말의 규칙을 그대로 따른다. 하나라도 빠지면 오류
 * 없이 값만 틀린다.
 *
 * <ol>
 *   <li><b>Distributed 테이블(listen_events)만 읽는다.</b> 한 재생의 이벤트가 두 샤드에
 *       흩어져 있을 수 있다 — Kafka 적재 경로가 샤딩 키를 타지 않는다. 그래서 JOIN 없이
 *       <b>한 번 훑어 {@code GROUP BY play_id}</b> 로 접는다. 분산 표끼리 JOIN 하면
 *       샤드마다 반쪽 재생을 보게 된다.</li>
 *   <li><b>{@code GROUP BY event_id} 로 먼저 누른다.</b> 병합 전 중복 행이 하트비트
 *       청취 시간을 부풀린다.</li>
 *   <li><b>시각은 문자열로 보내고 숫자로 받는다.</b></li>
 * </ol>
 *
 * <h2>재생은 시작한 시간에 속한다</h2>
 *
 * 재생 칸을 정하는 것은 {@code PLAY_START} 의 시각이다. 그런데 그 재생의 하트비트·스킵·
 * 종료는 <b>다음 시간으로 넘어가서</b> 들어올 수 있다. 그래서 이벤트는 창의 끝보다
 * {@code trailing} 만큼 더 읽고, 칸을 고르는 조건은 {@code HAVING} 에서 시작 시각으로
 * 건다. 창 앞쪽으로는 더 읽을 필요가 없다 — 재생의 이벤트는 시작보다 먼저 오지 않는다.
 *
 * <h2>쓰기는 로컬 표에, 읽기도 로컬 표에</h2>
 *
 * {@link TrackSectionRepository} 와 같은 이유다. {@code mupid_all}(1 샤드 × 2 레플리카)라
 * 어느 노드의 로컬 표에 넣어도 복제로 퍼지고, Distributed 로 넣으면 비동기라 INSERT 가
 * 돌아온 뒤에도 아직 없을 수 있다. 그것을 끄는 {@code SETTINGS} 절은 JDBC 파서가 모른다.
 *
 * <p>읽을 때는 <b>{@code FINAL}</b> 을 붙인다. ReplacingMergeTree 라 다시 계산한 칸의 옛
 * 행이 병합 전까지 함께 남아 있고, 빼면 그 칸이 두 번 더해진다. 표가 시간당 다섯 줄이라
 * {@code FINAL} 의 비용은 문제가 되지 않는다.
 */
@Repository
public class AiPreferenceRepository {

    /** 집계의 시간대. {@code occurred_at}·{@code hour} 가 이 시간대로 선언돼 있다. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * "들었다" 고 칠 최소 청취 시간(ms).
     *
     * <p>30초는 스트리밍 업계가 재생 한 번으로 세는 관행적인 문턱이다. 우리 곡은 거의 전부
     * 2분을 넘어 끝까지 듣기 전에 넘기는 일이 흔한데, 완주만 세면 "마음에 들어 절반쯤
     * 듣다 다음 곡으로 넘긴" 재생이 전부 실패로 잡힌다.
     */
    public static final long ENGAGED_MS = 30_000L;

    /** ClickHouse {@code toDateTime64(..., 3, 'Asia/Seoul')} 이 그대로 읽는 형식. */
    private static final DateTimeFormatter CLICKHOUSE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** {@code DateTime}(초 단위) 컬럼에 맞춘 형식. */
    private static final DateTimeFormatter CLICKHOUSE_SECOND =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 재생 밖의 이벤트({@code SEARCH} 등)에 적재기가 넣는 {@code play_id}. */
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    /**
     * 원본을 재생 단위로 접어 시간 × 구분으로 센다.
     *
     * <p>구분 이름은 {@link AiPreferenceSegment} 와 글자까지 같아야 한다. {@code rec_type}
     * 이 모르는 값이면 {@code UNTYPED} 로 모은다.
     *
     * <p>별칭은 원본 컬럼 이름과 겹치지 않게 지었다 ({@code heard_ms}, {@code uid0}).
     * ClickHouse 는 별칭을 쿼리 전체에서 치환해서, 바깥의 {@code sum(listened_ms) AS
     * listened_ms} 같은 모양은 순환 별칭 오류가 난다.
     */
    private static final String AGGREGATE = """
        SELECT toUnixTimestamp(toStartOfHour(started_at))  AS hour_s,
               segment,
               count()                                     AS plays,
               uniqExact(uid)                              AS users,
               countIf(heard_ms >= %1$d OR ended)          AS engaged_plays,
               countIf(ended)                              AS completed_plays,
               countIf(skipped)                            AS skipped_plays,
               countIf(skipped AND heard_ms < %1$d)        AS early_skip_plays,
               countIf(liked)                              AS liked_plays,
               sum(heard_ms)                               AS listened_ms
        FROM (
            SELECT pid,
                   minIf(at, kind = 'PLAY_START')          AS started_at,
                   anyIf(uid0, kind = 'PLAY_START')        AS uid,
                   anyIf(path, kind = 'PLAY_START')        AS start_path,
                   anyIf(rid, kind = 'PLAY_START')         AS start_rec_id,
                   anyIf(rtype, kind = 'PLAY_START')       AS start_rec_type,
                   multiIf(start_path != 'RECOMMEND' OR start_rec_id = '', 'NON_AI',
                           start_rec_type IN ('HOME', 'SECTION', 'CUSTOM'), start_rec_type,
                           'UNTYPED')                      AS segment,
                   sumIf(ms, kind = 'PLAY_PROGRESS')       AS heard_ms,
                   countIf(kind = 'PLAY_END') > 0          AS ended,
                   countIf(kind = 'TRACK_SKIP') > 0        AS skipped,
                   countIf(kind = 'LIKE') > 0              AS liked
            FROM (
                SELECT any(play_id)     AS pid,
                       any(event_type)  AS kind,
                       any(occurred_at) AS at,
                       any(user_id)     AS uid0,
                       any(entry_path)  AS path,
                       any(rec_id)      AS rid,
                       any(rec_type)    AS rtype,
                       greatest(toInt64(any(position_ms)) - toInt64(any(from_ms)), 0) AS ms
                FROM listen_events
                WHERE occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                  AND play_id != toUUID(?)
                  AND event_type IN ('PLAY_START', 'PLAY_PROGRESS', 'PLAY_END', 'TRACK_SKIP', 'LIKE')
                GROUP BY event_id
            )
            GROUP BY pid
            HAVING countIf(kind = 'PLAY_START') > 0
               AND started_at >= toDateTime64(?, 3, 'Asia/Seoul')
               AND started_at <  toDateTime64(?, 3, 'Asia/Seoul')
        )
        GROUP BY hour_s, segment
        ORDER BY hour_s, segment
        """.formatted(ENGAGED_MS);

    /** 컬럼 순서는 {@code clickhouse/init/01-schema.sql} 의 선언 순서와 같다. */
    private static final String INSERT = """
        INSERT INTO ai_preference_hourly_local
            (hour, segment, plays, users, engaged_plays, completed_plays,
             skipped_plays, early_skip_plays, liked_plays, listened_ms, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String READ = """
        SELECT toUnixTimestamp(hour) AS hour_s,
               segment, plays, users, engaged_plays, completed_plays,
               skipped_plays, early_skip_plays, liked_plays, listened_ms
        FROM ai_preference_hourly_local FINAL
        WHERE hour >= toDateTime(?, 'Asia/Seoul')
          AND hour <  toDateTime(?, 'Asia/Seoul')
        ORDER BY hour, segment
        """;

    private static final String LAST_COMPUTED = """
        SELECT toUnixTimestamp64Milli(max(computed_at)) AS computed_ms, count() AS n
        FROM ai_preference_hourly_local
        WHERE hour >= toDateTime(?, 'Asia/Seoul')
          AND hour <  toDateTime(?, 'Asia/Seoul')
        """;

    private final JdbcTemplate clickHouse;

    public AiPreferenceRepository(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    /**
     * {@code [from, to)} 에 <b>시작한</b> 재생을 시간 × 구분으로 센다.
     *
     * <p><b>재생이 없던 칸은 행이 없다.</b> 0 으로 채우는 것은 배치다.
     *
     * @param from     창의 시작. 정시여야 한다
     * @param to       창의 끝(제외). 정시여야 한다
     * @param trailing 창의 끝 뒤로 이벤트를 더 읽을 시간. 이보다 길게 이어진 재생은 꼬리가
     *                 잘린다 — 하트비트가 빠져 청취 시간이 작게 잡히고, 종료·스킵을 못 본다
     */
    public List<AiPreferenceHourRow> aggregate(LocalDateTime from, LocalDateTime to, Duration trailing) {
        return clickHouse.query(AGGREGATE,
            (rs, rowNum) -> row(rs),
            millis(from), millis(to.plus(trailing)), ZERO_UUID, millis(from), millis(to));
    }

    /**
     * 칸들을 쌓는다. 같은 칸이 이미 있으면 <b>이번 것이 이긴다</b> — 버전 컬럼
     * {@code computed_at} 에 한 회차 동안 같은 값을 넣어, 한 번의 적재가 한 덩어리로 이긴다.
     *
     * @return 넣은 행 수
     */
    public int replace(List<AiPreferenceHourRow> rows, LocalDateTime computedAt) {
        if (rows.isEmpty()) {
            return 0;
        }
        String version = millis(computedAt);
        List<Object[]> batch = rows.stream()
            .map(row -> new Object[] {
                seconds(row.hour()), row.segment().name(), row.plays(), row.users(),
                row.engagedPlays(), row.completedPlays(), row.skippedPlays(),
                row.earlySkipPlays(), row.likedPlays(), row.listenedMs(), version})
            .toList();
        clickHouse.batchUpdate(INSERT, batch);
        return rows.size();
    }

    /** 쌓아 둔 칸을 {@code [from, to)} 만큼 읽는다. 시간·구분 오름차순. */
    public List<AiPreferenceHourRow> read(LocalDateTime from, LocalDateTime to) {
        return clickHouse.query(READ, (rs, rowNum) -> row(rs), seconds(from), seconds(to));
    }

    /**
     * {@code [from, to)} 칸들이 마지막으로 계산된 시각. 한 칸도 없으면 {@code null} 이다 —
     * 화면이 "아직 집계되지 않았다" 와 "재생이 없었다" 를 가르는 근거다.
     */
    public Instant lastComputedAt(LocalDateTime from, LocalDateTime to) {
        List<Instant> found = clickHouse.query(LAST_COMPUTED,
            (rs, rowNum) -> rs.getLong("n") == 0 ? null : Instant.ofEpochMilli(rs.getLong("computed_ms")),
            seconds(from), seconds(to));
        return found.isEmpty() ? null : found.get(0);
    }

    private static AiPreferenceHourRow row(ResultSet rs) throws SQLException {
        return new AiPreferenceHourRow(
            LocalDateTime.ofInstant(Instant.ofEpochSecond(rs.getLong("hour_s")), ZONE),
            AiPreferenceSegment.parse(rs.getString("segment")),
            rs.getLong("plays"),
            rs.getLong("users"),
            rs.getLong("engaged_plays"),
            rs.getLong("completed_plays"),
            rs.getLong("skipped_plays"),
            rs.getLong("early_skip_plays"),
            rs.getLong("liked_plays"),
            rs.getLong("listened_ms"));
    }

    private static String millis(LocalDateTime time) {
        return time.format(CLICKHOUSE_TIME);
    }

    /** 밀리초가 붙으면 {@code DateTime} 컬럼과 {@code toDateTime} 이 거절한다. */
    private static String seconds(LocalDateTime time) {
        return time.withNano(0).format(CLICKHOUSE_SECOND);
    }

}
