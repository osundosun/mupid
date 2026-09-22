package com.osundosun.mupid.api.analytics.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.UserActivityDay;
import com.osundosun.mupid.api.analytics.dto.UserEventCount;
import com.osundosun.mupid.api.analytics.dto.UserEventRow;
import com.osundosun.mupid.api.analytics.dto.UserPlaySession;

/**
 * 사용자 한 명의 **행동 이벤트 자체**를 ClickHouse 에서 읽는다 (S15P21A204-430).
 *
 * <h2>{@link ListenAnalyticsRepository} 와 무엇이 다른가</h2>
 *
 * 그쪽은 MUBTI·리포트가 쓰는 <b>판정용 집계</b>다 — 곡별 청취 시간, 스킵 비율처럼
 * 이벤트를 숫자로 접어 놓은 것이라 "언제 무엇을 했는가" 가 남지 않는다. 관리자 화면은
 * 반대로 <b>원본 한 줄 한 줄</b>과 그 시각이 필요하다. 같은 파일에 두면 두 목적이 섞여
 * 어느 쿼리를 고쳐도 되는지 알 수 없게 된다.
 *
 * <h2>{@link ListenAnalyticsRepository} 와 같은 것</h2>
 *
 * 세 가지 규칙은 그대로 따른다. <b>이 셋 중 하나라도 빠지면 조용히 틀린 값이 나온다.</b>
 *
 * <ol>
 *   <li><b>Distributed 테이블(listen_events)만 읽는다.</b> Kafka 적재 경로가 샤딩 키를
 *       타지 않아 한 사용자의 이벤트가 두 샤드에 흩어진다 — 로컬 테이블을 읽으면 하루치가
 *       절반만 온다.</li>
 *   <li><b>{@code GROUP BY event_id} 로 감싼다.</b> 원본이
 *       {@code ReplicatedReplacingMergeTree} 라 병합 전에는 같은 이벤트가 여러 행으로
 *       남아 있다. 타임라인에서는 같은 항목이 겹쳐 그려지고, 청취 시간은 부풀려진다.</li>
 *   <li><b>시각은 문자열로 넘긴다.</b> {@code occurred_at} 이
 *       {@code DateTime64(3, 'Asia/Seoul')} 이므로 드라이버의 시간대 해석에 하루 경계가
 *       좌우되지 않게 {@code toDateTime64(?, 3, 'Asia/Seoul')} 로 못 박는다.</li>
 * </ol>
 *
 * <h2>시각은 숫자로 돌려받는다</h2>
 *
 * 보낼 때와 달리 <b>받을 때는 epoch ms</b>({@code toUnixTimestamp64Milli})다. 숫자에는
 * 시간대가 없어서 드라이버가 끼어들 자리가 없고, 자정과의 차이를 호출부가 그대로 뺄 수
 * 있다. {@code Timestamp} 로 받으면 JVM 기본 시간대에 따라 항목이 몇 시간씩 밀린다.
 *
 * <h2>UInt32 뺄셈</h2>
 *
 * {@code position_ms - from_ms} 를 그냥 빼지 않는다. 뒤집힌 하트비트(진행이 없었다는
 * 뜻)에서 <b>언더플로가 나 40억에 가까운 양수</b>가 되고, 그 한 건이 그날 청취 시간을
 * 통째로 망가뜨린다. {@code toInt64} 로 올린 뒤 빼고 {@code greatest(…, 0)} 으로 막는다 —
 * {@link ListenAnalyticsRepository} 가 {@code WHERE position_ms > from_ms} 로 거르는
 * 것과 같은 방어다. 여기서는 하트비트 아닌 이벤트도 함께 세야 해서 WHERE 로 거를 수 없다.
 */
@Repository
public class UserActivityRepository {

    /** ClickHouse {@code toDateTime64(..., 3, 'Asia/Seoul')} 이 그대로 읽는 형식. */
    private static final DateTimeFormatter CLICKHOUSE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * {@code play_id} 가 없는 이벤트에 적재기가 넣는 값.
     *
     * <p>{@code SEARCH} 처럼 재생 밖에서 일어난 이벤트에는 {@code play_id} 가 없고
     * {@code mv_kafka_to_events} 의 {@code toUUIDOrZero} 가 이 값을 넣는다. 재생을 셀 때
     * 빼지 않으면 <b>검색만 한 날에도 재생 1회</b>가 된다.
     */
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    private final JdbcTemplate clickHouse;

    public UserActivityRepository(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    /**
     * 날짜별 활동량. {@code from} 부터 {@code toExclusive} 직전까지, 날짜 오름차순.
     *
     * <p><b>기록이 없는 날은 행이 없다.</b> 호출부가 0 으로 채운다 — 빠뜨리면 화면의
     * 날짜 막대에 구멍이 생겨 "기록 없음" 과 "그 날이 범위 밖" 을 구분할 수 없다.
     *
     * <p>날짜를 문자열로 받는다. {@code java.sql.Date} 로 받으면 드라이버가 JVM 기본
     * 시간대로 되돌리면서 자정 근처의 날이 하루 밀린다 — 시각을 숫자로 받는 것과 같은
     * 이유다.
     */
    public List<UserActivityDay> dailyActivity(int userId, LocalDate from, LocalDate toExclusive) {
        return clickHouse.query("""
            SELECT toString(toDate(at, 'Asia/Seoul')) AS day,
                   count()                            AS events,
                   uniqExactIf(pid, pid != toUUID(?)) AS plays,
                   uniqExactIf(tid, tid > 0)          AS tracks,
                   sum(ms)                            AS listened_ms
            FROM (
                SELECT any(occurred_at) AS at,
                       any(play_id)     AS pid,
                       any(track_id)    AS tid,
                       if(any(event_type) = 'PLAY_PROGRESS',
                          greatest(toInt64(any(position_ms)) - toInt64(any(from_ms)), 0),
                          0)            AS ms
                FROM listen_events
                WHERE user_id = ?
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                GROUP BY event_id
            )
            GROUP BY day
            ORDER BY day
            """,
            (rs, rowNum) -> new UserActivityDay(
                rs.getString("day"),
                rs.getLong("events"),
                rs.getLong("plays"),
                rs.getLong("tracks"),
                rs.getLong("listened_ms")),
            ZERO_UUID, userId, startOf(from), startOf(toExclusive));
    }

    /**
     * 하루치 이벤트를 <b>종류별로</b> 센다. 밴드가 잘렸는지 판단하는 근거다.
     *
     * <p>목록({@link #events})에 상한이 걸려 있어 화면이 받은 수만으로는 전체를 알 수 없다.
     * 세는 것은 싸므로(정렬 키 선두가 {@code user_id} 다) 따로 한 번 물어본다 — 조용히 잘린
     * 밴드는 "이 사람은 그날 그것밖에 안 했다" 는 거짓말이 된다.
     */
    public List<UserEventCount> eventCountsByType(int userId, LocalDate date) {
        return clickHouse.query("""
            SELECT kind, count() AS events
            FROM (
                SELECT any(event_type) AS kind
                FROM listen_events
                WHERE user_id = ?
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                GROUP BY event_id
            )
            GROUP BY kind
            """,
            (rs, rowNum) -> new UserEventCount(rs.getString("kind"), rs.getLong("events")),
            userId, startOf(date), startOf(date.plusDays(1)));
    }

    /**
     * 하루치 이벤트 목록. 시각 오름차순.
     *
     * <p><b>{@code PLAY_PROGRESS} 는 빼고 가져온다.</b> 5초 하트비트라 한 곡만 들어도 수십
     * 건이고, 점으로 늘어놓으면 시간축이 그것으로 덮여 나머지 행동이 보이지 않는다.
     * 하트비트는 {@link #playSessions} 의 막대와 청취 시간으로 남는다.
     *
     * <p>{@code limit} 은 응답 크기를 막는 상한이다. 걸리면 <b>그날의 뒤쪽이 잘린다</b>(시각
     * 오름차순이므로). 호출부가 {@link #eventCountsByType} 의 합과 비교해 잘렸다고 말한다.
     *
     * <p>{@code ORDER BY} 에 식별자를 함께 둔 것은 <b>같은 밀리초에 여러 건이 있을 때 순서가
     * 매번 달라지지 않게</b> 하려는 것이다. 순서가 흔들리면 상한에 걸렸을 때 새로고침마다
     * 다른 행이 잘린다.
     */
    public List<UserEventRow> events(int userId, LocalDate date, int limit) {
        return clickHouse.query("""
            SELECT id, at_ms, kind, tid, pid, entry, pos, from_pos,
                   skip, reaction_target, query, playlist, comment, fail_reason
            FROM (
                SELECT toString(event_id)                        AS id,
                       toUnixTimestamp64Milli(any(occurred_at))  AS at_ms,
                       any(event_type)                           AS kind,
                       any(track_id)                             AS tid,
                       toString(any(play_id))                    AS pid,
                       any(entry_path)                           AS entry,
                       any(position_ms)                          AS pos,
                       any(from_ms)                              AS from_pos,
                       any(skip_trigger)                         AS skip,
                       any(target)                               AS reaction_target,
                       any(search_query)                         AS query,
                       any(playlist_id)                          AS playlist,
                       any(comment_id)                           AS comment,
                       any(reason)                               AS fail_reason
                FROM listen_events
                WHERE user_id = ?
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                  AND event_type != 'PLAY_PROGRESS'
                GROUP BY event_id
            )
            ORDER BY at_ms, id
            LIMIT ?
            """,
            (rs, rowNum) -> new UserEventRow(
                rs.getString("id"),
                rs.getLong("at_ms"),
                rs.getString("kind"),
                rs.getInt("tid"),
                rs.getString("pid"),
                rs.getString("entry"),
                rs.getInt("pos"),
                rs.getInt("from_pos"),
                rs.getString("skip"),
                rs.getString("reaction_target"),
                rs.getString("query"),
                rs.getInt("playlist"),
                rs.getInt("comment"),
                rs.getString("fail_reason")),
            userId, startOf(date), startOf(date.plusDays(1)), limit);
    }

    /**
     * 하루치 <b>재생 세션</b>. {@code play_id} 로 묶은 것이고 시작 시각 오름차순이다.
     *
     * <p>영(zero) UUID 는 제외한다 — 재생 밖의 이벤트({@code SEARCH})가 한 덩어리로 묶여
     * "하루 종일 이어진 재생" 이라는 거짓 막대가 된다.
     *
     * <p>곡과 진입 경로를 {@code max()} 로 뽑는다. {@code any()} 는 어느 행이 걸릴지
     * 모르는데 <b>재생의 이벤트 중에는 그 값이 비어 있는 것이 섞인다</b> — 재생 중 누른
     * {@code LIKE} 는 {@code entry_path} 를 싣지 않는다. {@code max} 는 빈 문자열·0 보다
     * 실제 값을 고른다. 한 재생은 한 곡이므로 값이 갈릴 일은 없다.
     */
    public List<UserPlaySession> playSessions(int userId, LocalDate date, int limit) {
        return clickHouse.query("""
            SELECT toString(pid) AS play_id,
                   min(at_ms)    AS started_ms,
                   max(at_ms)    AS ended_ms,
                   max(tid)      AS track_id,
                   max(entry)    AS entry_path,
                   count()       AS events,
                   sum(ms)       AS listened_ms
            FROM (
                SELECT any(play_id)                              AS pid,
                       toUnixTimestamp64Milli(any(occurred_at))  AS at_ms,
                       any(track_id)                             AS tid,
                       any(entry_path)                           AS entry,
                       if(any(event_type) = 'PLAY_PROGRESS',
                          greatest(toInt64(any(position_ms)) - toInt64(any(from_ms)), 0),
                          0)                                     AS ms
                FROM listen_events
                WHERE user_id = ?
                  AND occurred_at >= toDateTime64(?, 3, 'Asia/Seoul')
                  AND occurred_at <  toDateTime64(?, 3, 'Asia/Seoul')
                  AND play_id != toUUID(?)
                GROUP BY event_id
            )
            GROUP BY pid
            ORDER BY started_ms, play_id
            LIMIT ?
            """,
            (rs, rowNum) -> new UserPlaySession(
                rs.getString("play_id"),
                rs.getLong("started_ms"),
                rs.getLong("ended_ms"),
                rs.getInt("track_id"),
                rs.getString("entry_path"),
                rs.getLong("events"),
                rs.getLong("listened_ms")),
            userId, startOf(date), startOf(date.plusDays(1)), ZERO_UUID, limit);
    }

    private static String startOf(LocalDate date) {
        return CLICKHOUSE_TIME.format(LocalDateTime.of(date, LocalTime.MIDNIGHT));
    }

}
