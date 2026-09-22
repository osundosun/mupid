package com.osundosun.mupid.api.admin.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminUserEventGroup;
import com.osundosun.mupid.api.admin.dto.AdminUserEventType;
import com.osundosun.mupid.api.admin.dto.AdminUserTimelineRange;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.admin.response.AdminUserActivityDay;
import com.osundosun.mupid.api.admin.response.AdminUserEvent;
import com.osundosun.mupid.api.admin.response.AdminUserEventBand;
import com.osundosun.mupid.api.admin.response.AdminUserTimelineResponse;
import com.osundosun.mupid.api.analytics.dto.UserActivityDay;
import com.osundosun.mupid.api.analytics.dto.UserEventCount;
import com.osundosun.mupid.api.analytics.dto.UserEventRow;
import com.osundosun.mupid.api.analytics.dto.UserPlaySession;
import com.osundosun.mupid.api.analytics.repository.UserActivityRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AdminUserTimelineService} 구현.
 *
 * <h2>질의 수는 날 수·행 수와 무관하다</h2>
 *
 * ClickHouse 4회(날짜 집계 1 · 종류별 건수 1 · 이벤트 목록 1 · 재생 세션 1), MySQL 2회
 * (회원 1 · 곡 제목 {@code IN} 1). <b>창이 92일이든 7일이든, 이벤트가 열 건이든 천 건이든
 * 같다.</b> 날마다 물어보거나 곡마다 제목을 찾으면 그대로 N+1 이 된다.
 *
 * <h2>화면에 없는 날을 만들어 주는 것은 여기다</h2>
 *
 * ClickHouse 는 기록이 없는 날에 행을 주지 않는다. 그대로 내보내면 날짜 막대에 구멍이
 * 생기고, 화면은 "그날은 아무것도 안 했다" 와 "그날은 조회 범위 밖" 을 구분할 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserTimelineServiceImpl implements AdminUserTimelineService {

    /**
     * 날짜의 기준 시간대.
     *
     * <p>{@code occurred_at} 이 {@code DateTime64(3, 'Asia/Seoul')} 이므로 하루의 경계도
     * 그 시간대여야 한다. <b>서버의 기본 시간대를 쓰면 안 된다</b> — 운영 컨테이너가 UTC 로
     * 뜨는 순간 자정이 아홉 시간 어긋나 이벤트가 옆날로 넘어간다.
     *
     * <p>공개해 둔 것은 컨트롤러가 "오늘" 을 같은 시간대로 구해야 하기 때문이다. 거기서
     * {@code LocalDate.now()} 를 부르면 창의 상한(미래 금지)이 서버 시간대에 따라 하루
     * 어긋난다. {@code ReportServiceImpl.ZONE} 을 같은 이유로 공개해 두었다.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 하루의 이벤트를 몇 건까지 실어 보내는가.
     *
     * <p>상한을 두는 것은 이상한 하루가 응답을 수 MB 로 만들지 못하게 하려는 것이다.
     * 5초 하트비트를 이미 뺀 수라서 여유가 크다 — 실제로 무거운 하루도 수백 건이다.
     * 걸리면 <b>그날의 뒤쪽이 잘리고</b> 밴드의 {@code truncated} 가 참이 된다.
     */
    private static final int MAX_EVENT_ROWS = 800;

    /** 재생 세션 상한. 한 재생이 여러 이벤트를 접은 것이라 수가 훨씬 적다. */
    private static final int MAX_SESSION_ROWS = 300;

    /** 재생 밖의 이벤트에 적재기가 넣는 {@code play_id}. 화면에는 보내지 않는다. */
    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    private final UserRepository userRepository;
    private final MusicRepository musicRepository;
    private final UserActivityRepository userActivityRepository;

    @Override
    public AdminUserTimelineResponse timeline(int userId, AdminUserTimelineRange range) {
        // 탈퇴 계정도 찾는다. 이벤트는 남아 있고, 그 기록을 보는 것이 이 화면의 쓰임이다.
        User user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new BusinessException(AdminErrorCode.RECORD_NOT_FOUND));

        List<UserActivityDay> raw = read(userId,
            () -> userActivityRepository.dailyActivity(userId, range.from(), range.toExclusive()));
        Map<LocalDate, UserActivityDay> byDate = new HashMap<>();
        for (UserActivityDay day : raw) {
            byDate.put(LocalDate.parse(day.date()), day);
        }

        LocalDate date = pickDate(range, byDate);
        List<AdminUserActivityDay> days = fillDays(range, byDate, date);

        List<UserEventCount> counts = read(userId,
            () -> userActivityRepository.eventCountsByType(userId, date));
        List<UserEventRow> events = read(userId,
            () -> userActivityRepository.events(userId, date, MAX_EVENT_ROWS));
        List<UserPlaySession> sessions = read(userId,
            () -> userActivityRepository.playSessions(userId, date, MAX_SESSION_ROWS));

        Map<Integer, String> titles = titles(events, sessions);
        long midnightMs = date.atStartOfDay(ZONE).toInstant().toEpochMilli();

        UserActivityDay selected = byDate.get(date);
        long plays = selected == null ? 0 : selected.plays();
        List<AdminUserEventBand> bands = bands(events, sessions, counts, titles, midnightMs, plays);

        return new AdminUserTimelineResponse(
            user.getUserId(),
            user.getEmail(),
            user.getNickname(),
            user.getStatus() == null ? null : user.getStatus().name(),
            range.from(),
            range.to(),
            date,
            ZONE.getId(),
            !raw.isEmpty(),
            days,
            selected == null ? 0 : selected.events(),
            selected == null ? 0 : selected.listenedMs(),
            countOf(counts, AdminUserEventType.HEARTBEAT),
            bands);
    }

    // ----------------------------------------------------------------- 날짜 막대

    /**
     * 시간축에 펼칠 하루를 정한다.
     *
     * <p>요청이 날짜를 줬으면 그것이다. 없으면 <b>활동이 있었던 가장 최근 날</b>을 고른다 —
     * 창의 마지막 날을 그냥 쓰면 그 사람이 어제까지만 썼을 때 <b>빈 시간축</b>이 열리고,
     * 보는 사람은 기록이 없는 사용자로 읽는다. 창 전체에 활동이 없을 때만 마지막 날이다.
     */
    static LocalDate pickDate(AdminUserTimelineRange range, Map<LocalDate, UserActivityDay> byDate) {
        if (range.date() != null) {
            return range.date();
        }
        LocalDate found = null;
        for (Map.Entry<LocalDate, UserActivityDay> entry : byDate.entrySet()) {
            if (entry.getValue().events() <= 0) {
                continue;
            }
            if (found == null || entry.getKey().isAfter(found)) {
                found = entry.getKey();
            }
        }
        return found == null ? range.to() : found;
    }

    /**
     * 창의 모든 날을 하루씩 늘어놓는다. <b>기록이 없는 날은 0 으로 채운다.</b>
     *
     * <p>ClickHouse 가 없는 날을 만들어 주지 않으므로 여기서 채우지 않으면 막대에 구멍이
     * 생긴다. 화면이 날짜 축을 스스로 만들게 두면 그 축과 서버의 날짜 해석이 갈라진다.
     */
    private static List<AdminUserActivityDay> fillDays(
        AdminUserTimelineRange range,
        Map<LocalDate, UserActivityDay> byDate,
        LocalDate selected
    ) {
        List<AdminUserActivityDay> days = new ArrayList<>();
        for (LocalDate cursor = range.from(); !cursor.isAfter(range.to()); cursor = cursor.plusDays(1)) {
            UserActivityDay found = byDate.get(cursor);
            days.add(new AdminUserActivityDay(
                cursor,
                found == null ? 0 : found.events(),
                found == null ? 0 : found.plays(),
                found == null ? 0 : found.tracks(),
                found == null ? 0 : found.listenedMs(),
                cursor.equals(selected)));
        }
        return days;
    }

    // -------------------------------------------------------------------- 밴드

    /**
     * 밴드를 만든다. 순서는 {@link AdminUserEventGroup} 선언 순서다 — 재생 세션이 맨 위에
     * 오는 것이 이 화면의 등뼈이기 때문이다.
     *
     * <p>{@code OTHER} 는 <b>실제로 모르는 이벤트가 있었을 때만</b> 낸다. 늘 내면 대부분의
     * 날에 빈 줄이 하나 붙어, 읽어야 할 네 줄이 다섯 줄로 늘어난다. 나머지 네 밴드는
     * 비어 있어도 낸다 — 줄이 사라지면 "그 행동이 없었다" 와 "그런 밴드가 없다" 가
     * 구분되지 않는다.
     */
    private static List<AdminUserEventBand> bands(
        List<UserEventRow> events,
        List<UserPlaySession> sessions,
        List<UserEventCount> counts,
        Map<Integer, String> titles,
        long midnightMs,
        long plays
    ) {
        Map<AdminUserEventGroup, List<AdminUserEvent>> rows = new EnumMap<>(AdminUserEventGroup.class);
        Map<AdminUserEventGroup, Long> totals = new EnumMap<>(AdminUserEventGroup.class);
        for (AdminUserEventGroup group : AdminUserEventGroup.values()) {
            rows.put(group, new ArrayList<>());
            totals.put(group, 0L);
        }

        // 전체 건수는 목록이 아니라 **따로 센 값**에서 나온다. 목록은 상한에 걸려 잘릴 수
        // 있어서 그것으로 세면 잘린 사실이 사라진다.
        for (UserEventCount count : counts) {
            if (AdminUserEventType.isHeartbeat(count.eventType())) {
                // 하트비트는 점으로 그리지 않는다. 재생 세션 막대가 그 자리다 —
                // 여기서 더하면 재생 제어 밴드가 "천 건 중 열 건만 보임" 이 된다.
                continue;
            }
            AdminUserEventGroup group = AdminUserEventType.group(count.eventType());
            totals.merge(group, count.events(), Long::sum);
        }
        totals.put(AdminUserEventGroup.SESSION, plays);

        for (UserPlaySession session : sessions) {
            rows.get(AdminUserEventGroup.SESSION).add(sessionRow(session, titles, midnightMs));
        }
        for (UserEventRow event : events) {
            AdminUserEventGroup group = AdminUserEventType.group(event.eventType());
            rows.get(group).add(eventRow(event, titles, midnightMs));
        }

        List<AdminUserEventBand> bands = new ArrayList<>();
        for (AdminUserEventGroup group : AdminUserEventGroup.values()) {
            List<AdminUserEvent> found = rows.get(group);
            long total = totals.get(group);
            if (group == AdminUserEventGroup.OTHER && total == 0 && found.isEmpty()) {
                continue;
            }
            bands.add(new AdminUserEventBand(
                group.name(),
                group.label(),
                group.kind(),
                total,
                List.copyOf(found),
                found.size() < total));
        }
        return bands;
    }

    /**
     * 재생 한 번을 막대 하나로.
     *
     * <p>자정을 넘겨 이어진 재생은 그날의 끝에서 자른다({@link #atMs}). 자르지 않으면
     * 막대가 축 밖으로 나가 화면에서 폭이 잘못 읽힌다 — 같은 재생이 다음 날 타임라인에도
     * 앞부분이 잘린 채로 한 번 더 나온다. 같은 재생이 두 날에 걸쳐 있다는 사실 자체는
     * 사라지지 않는다.
     */
    private static AdminUserEvent sessionRow(
        UserPlaySession session,
        Map<Integer, String> titles,
        long midnightMs
    ) {
        int start = atMs(session.startedMs(), midnightMs);
        int end = Math.max(start, atMs(session.endedMs(), midnightMs));
        return new AdminUserEvent(
            session.playId(),
            AdminUserEvent.SESSION_TYPE,
            "재생",
            start,
            end,
            formatAt(start),
            session.trackId() > 0 ? session.trackId() : null,
            titles.get(session.trackId()),
            session.playId(),
            blankToNull(session.entryPath()),
            null,
            null,
            session.events(),
            session.listenedMs());
    }

    private static AdminUserEvent eventRow(
        UserEventRow event,
        Map<Integer, String> titles,
        long midnightMs
    ) {
        int at = atMs(event.occurredMs(), midnightMs);
        boolean hasTrack = event.trackId() > 0;
        return new AdminUserEvent(
            event.eventId(),
            event.eventType(),
            AdminUserEventType.label(event.eventType()),
            at,
            null,
            formatAt(at),
            hasTrack ? event.trackId() : null,
            hasTrack ? titles.get(event.trackId()) : null,
            playIdOrNull(event.playId()),
            blankToNull(event.entryPath()),
            // 곡이 없는 이벤트(`SEARCH`)에는 재생 위치라는 것이 없다. 0 을 그대로 보내면
            // 화면이 "곡 맨 앞에서 검색했다" 로 읽는다.
            hasTrack ? event.positionMs() : null,
            detailOf(event),
            null,
            null);
    }

    /**
     * 그 종류에만 있는 값 한 줄. 없으면 {@code null}.
     *
     * <p>모든 컬럼을 다 보내지 않고 <b>그 이벤트에서 뜻이 있는 것만</b> 고른다.
     * {@code listen_events} 는 한 표에 22종을 담고 있어서, 어떤 종류에는 뜻 없는 기본값
     * (빈 문자열·0)이 다른 종류의 컬럼에 그대로 들어 있다. 그대로 내보내면 화면이 그
     * 기본값을 값으로 그린다.
     */
    static String detailOf(UserEventRow event) {
        return switch (event.eventType()) {
            case "SEARCH" -> blankToNull(event.searchQuery());
            case "TRACK_SKIP" -> blankToNull(event.skipTrigger());
            case "LIKE", "UNLIKE", "DISLIKE", "UNDISLIKE" -> blankToNull(event.target());
            case "PLAY_ERROR" -> blankToNull(event.reason());
            case "PLAYLIST_ADD", "PLAYLIST_REMOVE" ->
                event.playlistId() > 0 ? "재생목록 #" + event.playlistId() : null;
            case "COMMENT_ADD", "COMMENT_DELETE" ->
                event.commentId() > 0 ? "댓글 #" + event.commentId() : null;
            // 되감기·앞으로감기는 "어디에서" 가 있어야 뜻이 산다. 도착점만으로는 앞으로
            // 갔는지 뒤로 갔는지 이미 종류가 말하고 있어서 새로운 정보가 없다.
            case "SEEK_BACK", "SEEK_FORWARD" -> formatPosition(event.fromMs()) + " 에서";
            default -> null;
        };
    }

    // -------------------------------------------------------------------- 도구

    /**
     * 곡 제목을 <b>한 번에</b> 받아 둔다. 행마다 찾으면 그대로 N+1 이다.
     *
     * <p>지워진 곡도 함께 받는다({@code findAllById}). 관리자는 "무엇에 대한 행동이었나"
     * 를 알아야 하고, 그 곡이 지금 지워졌다는 사실이 제목을 감출 이유가 되지 않는다.
     */
    private Map<Integer, String> titles(List<UserEventRow> events, List<UserPlaySession> sessions) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (UserEventRow event : events) {
            if (event.trackId() > 0) {
                ids.add(event.trackId());
            }
        }
        for (UserPlaySession session : sessions) {
            if (session.trackId() > 0) {
                ids.add(session.trackId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Integer, String> titles = new HashMap<>();
        for (Music music : musicRepository.findAllById(ids)) {
            titles.put(music.getMusicId(), music.getTitle());
        }
        return titles;
    }

    /**
     * 자정부터의 ms. 하루 밖으로 나간 값은 <b>양끝으로 자른다.</b>
     *
     * <p>자정을 넘겨 이어진 재생이 그렇다. 자르지 않으면 {@code left} 가 음수이거나 100%
     * 를 넘어 항목이 트랙 밖에 그려진다.
     */
    static int atMs(long occurredMs, long midnightMs) {
        long offset = occurredMs - midnightMs;
        if (offset < 0) {
            return 0;
        }
        return (int)Math.min(offset, AdminUserTimelineResponse.DAY_MS);
    }

    /** `HH:mm:ss`. 24:00:00(= 잘린 끝)도 그대로 나온다 — 하루의 끝이라는 뜻이다. */
    static String formatAt(int atMs) {
        int totalSeconds = atMs / 1000;
        return "%02d:%02d:%02d".formatted(
            totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    /** 곡 안의 위치. `m:ss`. */
    private static String formatPosition(int positionMs) {
        int totalSeconds = Math.max(positionMs, 0) / 1000;
        return "%d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private static Long countOf(List<UserEventCount> counts, String eventType) {
        for (UserEventCount count : counts) {
            if (eventType.equals(count.eventType())) {
                return count.events();
            }
        }
        return 0L;
    }

    /** 영(zero) UUID 는 "재생 밖" 이라는 뜻이다. 그대로 보내면 화면이 재생으로 읽는다. */
    private static String playIdOrNull(String playId) {
        return playId == null || playId.isBlank() || ZERO_UUID.equals(playId) ? null : playId;
    }

    /**
     * {@code LowCardinality(String)} 컬럼은 값이 없으면 {@code null} 이 아니라 <b>빈
     * 문자열</b>로 온다. 그대로 보내면 화면에 빈 칸이 붙는다.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * ClickHouse 조회를 감싼다. <b>실패를 500 으로 흘려보내지 않는다.</b>
     *
     * <p>분석 클러스터가 죽어 있는 것과 서버가 망가진 것은 다른 사건이고, 보는 사람이
     * 할 일도 다르다. 리포트가 {@code REPORT_ANALYTICS_UNAVAILABLE} 로 답하는 것과 같은
     * 판단이다 — 사유는 서버 로그에 남는다.
     */
    private <T> T read(int userId, Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            log.warn("관리자 행동 타임라인 조회 실패 user_id={}", userId, e);
            throw new BusinessException(AdminErrorCode.ANALYTICS_UNAVAILABLE);
        }
    }

}
