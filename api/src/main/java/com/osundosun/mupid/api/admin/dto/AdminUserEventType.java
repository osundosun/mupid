package com.osundosun.mupid.api.admin.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 이벤트 종류 22종을 **한글 이름과 밴드**로 옮기는 표 (S15P21A204-430).
 *
 * <h2>왜 이름과 밴드를 한 표에 두는가</h2>
 *
 * 둘을 따로 두면 표가 갈라진다 — 새 이벤트를 한쪽에만 적는 날이 오고, 그러면 이름은
 * 나오는데 밴드가 {@code OTHER} 이거나 그 반대가 된다. 한 줄에 둘 다 적으면 빠뜨릴 수 없다.
 *
 * <h2>표에 없는 종류</h2>
 *
 * {@code OTHER} 밴드로 가고 <b>이름은 원래 값 그대로</b>다. 번역을 지어내지 않는다 —
 * 모르는 이벤트에 그럴듯한 한글을 붙이면 보는 사람이 그것을 아는 것으로 읽는다.
 *
 * <p>목록의 근거는 {@code knowledge/domain/listening-events.md} 의 22종 표다.
 * 그 문서가 기준이고, 이 표는 그것을 화면 말로 옮긴 것뿐이다.
 */
public final class AdminUserEventType {

    /**
     * 이벤트 종류 → (한글 이름, 밴드).
     *
     * <p>선언 순서를 지킨다({@link LinkedHashMap}) — 이 순서가 곧 문서의 22종 표 순서라
     * 둘을 나란히 두고 대조할 수 있다.
     */
    private static final Map<String, Entry> TABLE = table();

    private AdminUserEventType() {
    }

    /** 이 종류의 한글 이름. 표에 없으면 <b>원래 값 그대로</b>. */
    public static String label(String eventType) {
        Entry entry = TABLE.get(eventType);
        return entry == null ? eventType : entry.label();
    }

    /** 이 종류가 들어갈 밴드. 표에 없으면 {@link AdminUserEventGroup#OTHER}. */
    public static AdminUserEventGroup group(String eventType) {
        Entry entry = TABLE.get(eventType);
        return entry == null ? AdminUserEventGroup.OTHER : entry.group();
    }

    /**
     * 5초 하트비트인가.
     *
     * <p><b>이 한 종류만 타임라인에 점으로 그리지 않는다.</b> 한 곡을 3분 들으면 서른여섯
     * 건이라, 점으로 늘어놓으면 시간축이 그것으로 덮여 나머지 행동이 보이지 않는다.
     * 대신 {@link AdminUserEventGroup#SESSION} 막대의 길이와 청취 시간으로 남는다.
     */
    public static boolean isHeartbeat(String eventType) {
        return HEARTBEAT.equals(eventType);
    }

    /** {@link #isHeartbeat} 가 가리키는 종류. 조회 쪽 {@code WHERE} 와 같은 값이다. */
    public static final String HEARTBEAT = "PLAY_PROGRESS";

    private static Map<String, Entry> table() {
        Map<String, Entry> table = new LinkedHashMap<>();
        AdminUserEventGroup playback = AdminUserEventGroup.PLAYBACK;
        AdminUserEventGroup navigation = AdminUserEventGroup.NAVIGATION;
        AdminUserEventGroup feedback = AdminUserEventGroup.FEEDBACK;

        table.put("PLAY_START", new Entry("재생 시작", playback));
        table.put(HEARTBEAT, new Entry("재생 진행", playback));
        table.put("PLAY_PAUSE", new Entry("일시정지", playback));
        table.put("PLAY_RESUME", new Entry("재개", playback));
        table.put("PLAY_END", new Entry("재생 완료", playback));
        table.put("PLAY_STALL", new Entry("버퍼링", playback));
        table.put("PLAY_ERROR", new Entry("재생 실패", playback));
        table.put("PLAYBACK_ABANDON", new Entry("앱 종료로 끊김", playback));

        table.put("SEEK_BACK", new Entry("되감기", navigation));
        table.put("SEEK_FORWARD", new Entry("앞으로감기", navigation));
        table.put("TRACK_SKIP", new Entry("곡 넘김", navigation));
        table.put("SEARCH", new Entry("검색", navigation));

        table.put("LIKE", new Entry("좋아요", feedback));
        table.put("UNLIKE", new Entry("좋아요 취소", feedback));
        table.put("DISLIKE", new Entry("관심 없음", feedback));
        table.put("UNDISLIKE", new Entry("관심 없음 취소", feedback));
        table.put("SECTION_MARK", new Entry("구간 지정", feedback));
        table.put("SECTION_UNMARK", new Entry("구간 해제", feedback));
        table.put("PLAYLIST_ADD", new Entry("재생목록 담기", feedback));
        table.put("PLAYLIST_REMOVE", new Entry("재생목록 빼기", feedback));
        table.put("COMMENT_ADD", new Entry("댓글 작성", feedback));
        table.put("COMMENT_DELETE", new Entry("댓글 삭제", feedback));

        // `Map.copyOf` 를 쓰지 않는다 — 그쪽은 순회 순서를 보장하지 않아 위 주석이
        // 거짓이 된다. 조회만 하는 표이지만, 문서의 22종 표와 나란히 두고 읽을 수 있어야
        // 빠진 줄이 눈에 띈다.
        return Collections.unmodifiableMap(table);
    }

    private record Entry(String label, AdminUserEventGroup group) {
    }

}
