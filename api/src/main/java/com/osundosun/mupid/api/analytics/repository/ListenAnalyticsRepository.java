package com.osundosun.mupid.api.analytics.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.DailyPlaySummary;
import com.osundosun.mupid.api.analytics.dto.DailyTrackListening;
import com.osundosun.mupid.api.analytics.dto.TrackPlayCount;

/**
 * ClickHouse 에서 청취 이벤트를 집계한다. **저장소에서 분석 클러스터를 읽는 첫 코드다.**
 * 지금까지 `analytics` 패키지에는 커넥션 풀 설정만 있고 읽는 곳이 없었다.
 *
 * <h2>왜 Distributed 테이블만 읽는가</h2>
 *
 * `listen_events_local` 은 `cityHash64(user_id)` 로 샤딩하도록 선언돼 있지만,
 * <b>Kafka 적재 경로는 그 샤딩 키를 타지 않는다.</b> 이벤트는 그것을 소비한 노드의
 * 로컬 테이블에 들어가므로 한 사용자의 이벤트가 두 샤드에 흩어진다. 사용자 단위
 * 집계는 반드시 Distributed 테이블(`listen_events`)에서 해야 한다 —
 * knowledge/domain/listening-events.md 의 경고 그대로다.
 *
 * <h2>왜 전부 `GROUP BY event_id` 로 감싸는가</h2>
 *
 * `listen_events_local` 이 `ReplicatedReplacingMergeTree(ingested_at)` 다. 중복
 * 제거는 <b>병합 시점에</b> 일어나고 그 시점을 보장할 수 없으므로, 병합 전에는 같은
 * `event_id` 가 여러 행으로 남아 있다. 수집기의 Redis 멱등(TTL 10분)을 넘긴 재전송이나
 * Kafka 재소비가 그대로 청취 시간 부풀리기가 된다. 한 사용자의 하루치 범위라 이
 * GROUP BY 는 싸다.
 *
 * <h2>왜 시각을 문자열로 넘기는가</h2>
 *
 * JDBC 드라이버가 `Timestamp` 를 어느 시간대로 보내는지에 서비스 결과가 좌우되면
 * 안 된다. `occurred_at` 은 `DateTime64(3, 'Asia/Seoul')` 이므로 문자열을 만들어
 * `toDateTime64(?, 3, 'Asia/Seoul')` 로 못 박는다. 수집기가 Kafka 로 보낼 때 쓰는
 * 형식과 같다 (`EventServiceImpl.CLICKHOUSE_TIME`).
 */
@Repository
public class ListenAnalyticsRepository {

    /** ClickHouse `toDateTime64(..., 3, 'Asia/Seoul')` 이 그대로 읽는 형식. */
    private static final DateTimeFormatter CLICKHOUSE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final JdbcTemplate clickHouse;

    public ListenAnalyticsRepository(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    /**
     * Q1. 곡별 청취량. 청취 시간 내림차순.
     *
     * `position_ms > from_ms` 로 거른다. 같거나 뒤집힌 하트비트는 진행이 없었다는
     * 뜻이고, 뒤집힌 값을 그대로 빼면 `UInt32` 언더플로로 터무니없는 양수가 된다.
     *
     * <b>서브쿼리의 집계 별칭을 `tid` 로 둔 것은 취향이 아니다.</b> `any(track_id) AS
     * track_id` 로 쓰면 ClickHouse 가 바깥의 `track_id` 를 서브쿼리 WHERE 로 밀어
     * 넣으면서 그 별칭과 부딪혀 `ILLEGAL_AGGREGATION` (코드 184) 으로 쿼리가 통째로
     * 실패한다 — "Aggregate function any(track_id) is found in WHERE". 컬럼과 이름이
     * 겹치지 않는 별칭을 쓴다.
     */
    public List<DailyTrackListening> trackListening(int userId, LocalDate date) {
        return clickHouse.query("""
            SELECT tid AS track_id, sum(ms) AS listened_ms
            FROM (
                SELECT
                    any(track_id)                   AS tid,
                    any(position_ms) - any(from_ms) AS ms
                FROM listen_events
                WHERE user_id = ?
                  AND event_type = 'PLAY_PROGRESS'
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                  AND track_id > 0
                  AND position_ms > from_ms
                GROUP BY event_id
            )
            GROUP BY tid
            ORDER BY listened_ms DESC
            """,
            (rs, rowNum) -> new DailyTrackListening(rs.getInt("track_id"), rs.getLong("listened_ms")),
            userId, startOf(date), startOf(date.plusDays(1)));
    }

    /**
     * Q2. 재생 행동 요약. 하루에 이벤트가 하나도 없으면 전부 0 이 담긴 한 행이 온다.
     *
     * 서브쿼리 별칭이 컬럼명과 겹치지 않는 이유는 Q1 과 같다.
     *
     * <b>재생 수에서 영(zero) UUID 를 빼는 이유.</b> `SEARCH` 처럼 재생 밖에서 일어난
     * 이벤트에는 `play_id` 가 없고, `mv_kafka_to_events` 의 `toUUIDOrZero` 가 그것을
     * `00000000-…` 으로 적재한다. 그냥 `uniqExact` 하면 그 하나가 "재생 1회" 로 세어져
     * 검색만 한 날에도 재생 수가 1 이 된다. 이 값은 C축의 앞으로감기 빈도와 D축의 반복
     * 강도에서 <b>분모·분자로 쓰이므로</b> 조용히 판정을 흔든다.
     */
    public DailyPlaySummary playSummary(int userId, LocalDate date) {
        List<DailyPlaySummary> rows = clickHouse.query("""
            SELECT
                countIf(kind = 'PLAY_END')     AS ends,
                countIf(kind = 'TRACK_SKIP')   AS skips,
                countIf(kind = 'PLAY_START')   AS starts,
                countIf(kind = 'SEEK_FORWARD') AS seek_forwards,
                countIf(kind = 'SEEK_BACK')    AS seek_backs,
                uniqExactIf(pid, pid != toUUID('00000000-0000-0000-0000-000000000000')) AS plays
            FROM (
                SELECT any(event_type) AS kind, any(play_id) AS pid
                FROM listen_events
                WHERE user_id = ?
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                GROUP BY event_id
            )
            """,
            (rs, rowNum) -> new DailyPlaySummary(
                rs.getLong("ends"),
                rs.getLong("skips"),
                rs.getLong("starts"),
                rs.getLong("seek_forwards"),
                rs.getLong("seek_backs"),
                rs.getLong("plays")),
            userId, startOf(date), startOf(date.plusDays(1)));
        return rows.isEmpty() ? DailyPlaySummary.EMPTY : rows.get(0);
    }

    /**
     * Q2-b. 곡별 재생 시도 횟수. `PLAY_START` 를 센다.
     *
     * <b>청취 시간(Q1)과 반드시 따로 읽어야 한다.</b> Q1 은 `PLAY_PROGRESS` 하트비트에서
     * 나오는데 하트비트는 5초 간격이라, 그 전에 넘긴 곡은 Q1 결과에 <b>아예 행이 없다.</b>
     * "빠르게 넘기며 새 곡을 디깅한다" 는 행동이 정확히 그 모양이라, 시간만 보면 가장
     * 활발히 탐색한 사람이 아무것도 안 한 것처럼 보인다.
     *
     * 실측으로도 재생 시도 28회 중 22회가 청취 시간 0 이었고, 재생 29회 중 17회가
     * 5초를 못 넘겼다.
     */
    public List<TrackPlayCount> playStartsByTrack(int userId, LocalDate date) {
        return clickHouse.query("""
            SELECT tid AS track_id, count() AS plays
            FROM (
                SELECT any(track_id) AS tid
                FROM listen_events
                WHERE user_id = ?
                  AND event_type = 'PLAY_START'
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                  AND track_id > 0
                GROUP BY event_id
            )
            GROUP BY tid
            ORDER BY plays DESC
            """,
            (rs, rowNum) -> new TrackPlayCount(rs.getInt("track_id"), rs.getLong("plays")),
            userId, startOf(date), startOf(date.plusDays(1)));
    }

    /**
     * Q3. 그날 이전 `lookbackDays` 일 동안 들어본 곡 번호.
     *
     * "오늘 처음 듣는 곡" 을 가려내는 데만 쓴다. **그날은 포함하지 않는다** — 오늘
     * 아침에 튼 곡이 오후에 "익숙한 곡" 이 되어 버리면 D축이 시간대에 따라 흔들린다.
     *
     * 이 결과는 그날 안에 변하지 않으므로 호출부가 길게 캐시한다.
     */
    public List<Integer> tracksHeardBefore(int userId, LocalDate date, int lookbackDays) {
        return clickHouse.query("""
            SELECT DISTINCT track_id
            FROM listen_events
            WHERE user_id = ?
              AND event_type IN ('PLAY_START', 'PLAY_PROGRESS')
              AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
              AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
              AND track_id > 0
            """,
            (rs, rowNum) -> rs.getInt("track_id"),
            userId, startOf(date.minusDays(lookbackDays)), startOf(date));
    }

    /**
     * 카탈로그 대중성 기준선 — 곡별 누적 재생 시도 횟수, <b>전 사용자·전 기간</b>.
     *
     * MuBTI B축이 "이 사람이 인기 있는 곡을 듣는가" 를 재는 근거다. 원래는 MySQL
     * `music.play_count` 를 봤는데 <b>그 컬럼을 올리는 코드가 저장소에 없어 전 곡이
     * 0 이었다.</b> 백분위가 전부 같아지면 그 항은 아무도 구분하지 못하는 상수가 된다.
     *
     * 재생 로그가 원천이라 컬럼보다 정확하고, <b>`track_id` 만 쓰므로 곡 메타데이터를
     * ClickHouse 에 복제할 필요가 없다.</b>
     *
     * <b>한 번도 재생되지 않은 곡은 여기 나오지 않는다.</b> 호출부가 카탈로그 크기를
     * 알아서 0 을 채워야 백분위가 맞는다 — `CatalogBaseline` 이 그렇게 한다.
     *
     * 사용자별 하루치 쿼리들과 달리 전 기간을 훑으므로 호출부가 길게 캐시한다.
     * `TTL 18개월` 이 범위를 묶어 준다.
     */
    public List<TrackPlayCount> catalogPlayCounts() {
        return clickHouse.query("""
            SELECT tid AS track_id, count() AS plays
            FROM (
                SELECT any(track_id) AS tid
                FROM listen_events
                WHERE event_type = 'PLAY_START' AND track_id > 0
                GROUP BY event_id
            )
            GROUP BY tid
            """,
            (rs, rowNum) -> new TrackPlayCount(rs.getInt("track_id"), rs.getLong("plays")));
    }

    /**
     * 그날 이벤트를 하나라도 남긴 사용자. 자정 배치가 대상을 좁히는 데 쓴다.
     *
     * 전체 회원을 훑으면 듣지도 않은 사용자 수만큼 ClickHouse 를 때린다. 이 쿼리는
     * `user_id` 가 정렬 키 선두라 하루 파티션을 훑어도 싸다.
     */
    public List<Integer> activeUsers(LocalDate date) {
        return clickHouse.query("""
            SELECT DISTINCT user_id
            FROM listen_events
            WHERE occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
              AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
              AND user_id > 0
            """,
            (rs, rowNum) -> rs.getInt("user_id"),
            startOf(date), startOf(date.plusDays(1)));
    }

    private static String startOf(LocalDate date) {
        return CLICKHOUSE_TIME.format(LocalDateTime.of(date, java.time.LocalTime.MIDNIGHT));
    }

}
