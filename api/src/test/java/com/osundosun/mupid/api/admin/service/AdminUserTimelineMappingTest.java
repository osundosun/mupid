package com.osundosun.mupid.api.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.admin.dto.AdminUserEventGroup;
import com.osundosun.mupid.api.admin.dto.AdminUserEventType;
import com.osundosun.mupid.api.admin.dto.AdminUserTimelineRange;
import com.osundosun.mupid.api.admin.response.AdminUserTimelineResponse;
import com.osundosun.mupid.api.analytics.dto.UserActivityDay;
import com.osundosun.mupid.api.analytics.dto.UserEventRow;

/**
 * 이벤트를 **시간축 위의 항목으로 옮기는 규칙** (S15P21A204-430).
 *
 * <p>ClickHouse 없이 돈다. 여기 있는 것은 산술과 표 조회뿐이고, 그 셋이 틀리면
 * <b>오류 없이 화면만 거짓말을 한다</b> — 인프라를 띄워야 돌릴 수 있는 검사로 두면
 * 아무도 돌리지 않게 된다.
 *
 * <h2>무엇을 못박는가</h2>
 *
 * <ol>
 *   <li><b>하루 밖으로 나간 시각을 양끝으로 자른다</b> — 자정을 넘겨 이어진 재생이
 *       그렇다. 자르지 않으면 {@code left} 가 음수이거나 100% 를 넘어 항목이 트랙 밖에
 *       그려진다.</li>
 *   <li><b>날짜를 지정하지 않으면 활동이 있었던 가장 최근 날</b> — 창의 마지막 날을 그냥
 *       쓰면 어제까지만 쓴 사용자에게 빈 시간축이 열리고, 보는 사람은 기록이 없는 회원으로
 *       읽는다.</li>
 *   <li><b>이벤트 22종이 빠짐없이 밴드를 갖는다</b> — 표에 없는 종류는 {@code OTHER} 로
 *       가는데, 아는 종류가 실수로 거기 섞이면 재생·피드백 줄이 조용히 비어 보인다.</li>
 *   <li><b>모르는 종류를 버리지 않는다</b> — 수집기의 목록은 늘어난다. 사라지면 화면은
 *       "그날 그 행동이 없었다" 고 거짓말을 한다.</li>
 * </ol>
 */
class AdminUserTimelineMappingTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 21);

    /** 그날 자정(`Asia/Seoul`). 서비스가 쓰는 것과 같은 방식으로 만든다. */
    private static final long MIDNIGHT = DATE
        .atStartOfDay(AdminUserTimelineServiceImpl.ZONE).toInstant().toEpochMilli();

    // ------------------------------------------------------------------ 시각

    @Test
    @DisplayName("자정은 0, 하루 끝은 축의 최대값이다")
    void mapsMidnightAndEndOfDay() {
        assertEquals(0, AdminUserTimelineServiceImpl.atMs(MIDNIGHT, MIDNIGHT));
        assertEquals(AdminUserTimelineResponse.DAY_MS,
            AdminUserTimelineServiceImpl.atMs(MIDNIGHT + AdminUserTimelineResponse.DAY_MS, MIDNIGHT));
    }

    @Test
    @DisplayName("한국 시간 09:30 은 그 시각의 ms 가 된다 — 서버 시간대를 타지 않는다")
    void mapsSeoulWallClock() {
        long at = ZonedDateTime.of(DATE.atTime(9, 30), AdminUserTimelineServiceImpl.ZONE)
            .toInstant().toEpochMilli();

        assertEquals((9 * 60 + 30) * 60 * 1000, AdminUserTimelineServiceImpl.atMs(at, MIDNIGHT));
    }

    @Test
    @DisplayName("하루 밖으로 나간 시각은 양끝으로 자른다")
    void clampsOutsideTheDay() {
        // 전날 저녁에 시작해 자정을 넘긴 재생.
        assertEquals(0, AdminUserTimelineServiceImpl.atMs(MIDNIGHT - 60_000, MIDNIGHT));
        // 다음 날 새벽까지 이어진 재생.
        assertEquals(AdminUserTimelineResponse.DAY_MS, AdminUserTimelineServiceImpl.atMs(
            MIDNIGHT + AdminUserTimelineResponse.DAY_MS + 60_000, MIDNIGHT));
    }

    @Test
    @DisplayName("시각 표기는 두 자리로 채운다")
    void formatsClock() {
        assertEquals("00:00:00", AdminUserTimelineServiceImpl.formatAt(0));
        assertEquals("09:05:03", AdminUserTimelineServiceImpl.formatAt(
            ((9 * 60 + 5) * 60 + 3) * 1000));
        // 하루의 끝(= 잘린 값)도 그대로 나온다. 23:59:59 로 내리면 잘렸다는 사실이 사라진다.
        assertEquals("24:00:00",
            AdminUserTimelineServiceImpl.formatAt(AdminUserTimelineResponse.DAY_MS));
    }

    // ------------------------------------------------------------ 펼칠 날 고르기

    @Test
    @DisplayName("요청이 날짜를 주면 그대로 쓴다")
    void usesRequestedDate() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            DATE.minusDays(6), DATE, DATE.minusDays(3), DATE);

        assertEquals(DATE.minusDays(3),
            AdminUserTimelineServiceImpl.pickDate(range, activity(DATE, 10)));
    }

    @Test
    @DisplayName("날짜가 없으면 활동이 있었던 가장 최근 날")
    void picksLatestActiveDay() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            DATE.minusDays(6), DATE, null, DATE);
        Map<LocalDate, UserActivityDay> days = activity(DATE.minusDays(5), 3);
        days.putAll(activity(DATE.minusDays(2), 7));

        assertEquals(DATE.minusDays(2), AdminUserTimelineServiceImpl.pickDate(range, days));
    }

    @Test
    @DisplayName("이벤트가 0 인 날은 고르지 않는다")
    void skipsEmptyDays() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            DATE.minusDays(6), DATE, null, DATE);
        Map<LocalDate, UserActivityDay> days = activity(DATE.minusDays(4), 5);
        // ClickHouse 가 이런 행을 주지는 않지만, 주더라도 빈 날로 끌려가면 안 된다.
        days.putAll(activity(DATE, 0));

        assertEquals(DATE.minusDays(4), AdminUserTimelineServiceImpl.pickDate(range, days));
    }

    @Test
    @DisplayName("활동이 하나도 없으면 창의 마지막 날")
    void fallsBackToWindowEnd() {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            DATE.minusDays(6), DATE, null, DATE);

        assertEquals(DATE, AdminUserTimelineServiceImpl.pickDate(range, new HashMap<>()));
    }

    // --------------------------------------------------------------- 종류 표

    @Test
    @DisplayName("이벤트 22종이 저마다 제 밴드로 간다 — 아는 종류가 기타로 새지 않는다")
    void everyKnownTypeHasItsBand() {
        assertGroup(AdminUserEventGroup.PLAYBACK, "PLAY_START", "PLAY_PROGRESS", "PLAY_PAUSE",
            "PLAY_RESUME", "PLAY_END", "PLAY_STALL", "PLAY_ERROR", "PLAYBACK_ABANDON");
        assertGroup(AdminUserEventGroup.NAVIGATION,
            "SEEK_BACK", "SEEK_FORWARD", "TRACK_SKIP", "SEARCH");
        assertGroup(AdminUserEventGroup.FEEDBACK, "LIKE", "UNLIKE", "DISLIKE", "UNDISLIKE",
            "SECTION_MARK", "SECTION_UNMARK", "PLAYLIST_ADD", "PLAYLIST_REMOVE",
            "COMMENT_ADD", "COMMENT_DELETE");
    }

    @Test
    @DisplayName("모르는 종류는 기타로 가고 이름을 지어내지 않는다")
    void keepsUnknownTypeVisible() {
        assertSame(AdminUserEventGroup.OTHER, AdminUserEventType.group("SOMETHING_NEW"));
        assertEquals("SOMETHING_NEW", AdminUserEventType.label("SOMETHING_NEW"));
    }

    @Test
    @DisplayName("하트비트는 한 종류뿐이다")
    void onlyProgressIsHeartbeat() {
        assertTrue(AdminUserEventType.isHeartbeat("PLAY_PROGRESS"));
        assertFalse(AdminUserEventType.isHeartbeat("PLAY_START"));
    }

    // ------------------------------------------------------------- 고유 정보

    @Test
    @DisplayName("그 종류에 뜻이 있는 값만 딸려 나온다")
    void detailPicksMeaningfulColumn() {
        assertEquals("아이유", AdminUserTimelineServiceImpl.detailOf(search("아이유")));
        assertEquals("NEXT_BUTTON", AdminUserTimelineServiceImpl.detailOf(event("TRACK_SKIP")));
        assertEquals("TRACK", AdminUserTimelineServiceImpl.detailOf(event("LIKE")));
        assertEquals("MEDIA_ERR_NETWORK", AdminUserTimelineServiceImpl.detailOf(event("PLAY_ERROR")));
        assertEquals("재생목록 #7", AdminUserTimelineServiceImpl.detailOf(event("PLAYLIST_ADD")));
        assertEquals("댓글 #9", AdminUserTimelineServiceImpl.detailOf(event("COMMENT_ADD")));
        assertEquals("1:05 에서", AdminUserTimelineServiceImpl.detailOf(event("SEEK_BACK")));
    }

    @Test
    @DisplayName("빈 문자열은 값이 아니다 — 화면에 빈 칸을 붙이지 않는다")
    void blankIsNotAValue() {
        // LowCardinality(String) 컬럼은 값이 없으면 null 이 아니라 빈 문자열로 온다.
        assertNull(AdminUserTimelineServiceImpl.detailOf(search("")));
        assertNull(AdminUserTimelineServiceImpl.detailOf(new UserEventRow(
            "e", MIDNIGHT, "PLAY_ERROR", 1, "p", "", 0, 0, "", "", "", 0, 0, "")));
    }

    @Test
    @DisplayName("재생 제어에는 딸려 나올 값이 없다")
    void playbackHasNoDetail() {
        assertNull(AdminUserTimelineServiceImpl.detailOf(event("PLAY_START")));
    }

    // -------------------------------------------------------------------- 도구

    private static void assertGroup(AdminUserEventGroup expected, String... eventTypes) {
        for (String eventType : eventTypes) {
            assertSame(expected, AdminUserEventType.group(eventType), eventType);
            // 한글 이름도 함께 있어야 한다. 표를 두 벌로 나누면 한쪽만 채우는 날이 온다.
            assertFalse(eventType.equals(AdminUserEventType.label(eventType)), eventType);
        }
    }

    private static Map<LocalDate, UserActivityDay> activity(LocalDate date, long events) {
        Map<LocalDate, UserActivityDay> days = new HashMap<>();
        days.put(date, new UserActivityDay(date.toString(), events, 1, 1, 1000));
        return days;
    }

    private static UserEventRow search(String query) {
        return new UserEventRow("e", MIDNIGHT, "SEARCH", 0, "p", "", 0, 0, "", "", query, 0, 0, "");
    }

    /**
     * 모든 고유 컬럼이 채워진 행. 어느 종류로 읽어도 값이 있으므로, {@code detailOf} 가
     * <b>고르지 않고 아무거나 집으면</b> 검사가 실패한다.
     */
    private static UserEventRow event(String eventType) {
        return new UserEventRow(
            "e", MIDNIGHT, eventType, 12, "p", "SEARCH", 90_000, 65_000,
            "NEXT_BUTTON", "TRACK", "아이유", 7, 9, "MEDIA_ERR_NETWORK");
    }

}
