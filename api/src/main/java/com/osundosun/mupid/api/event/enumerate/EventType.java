package com.osundosun.mupid.api.event.enumerate;

/**
 * 수집하는 청취 행동 22종. ClickHouse `listen_events.event_type` 에 그대로 들어간다.
 *
 * 이 목록에 없는 값은 수집기가 거부한다. 새 이벤트를 추가할 때는 여기와
 * `api/clickhouse/init/01-schema.sql`, `frontend/lib/analytics/types.ts` 를 같이 고친다.
 */
public enum EventType {

    PLAY_START,
    PLAY_PROGRESS,
    PLAY_PAUSE,
    PLAY_RESUME,
    PLAY_END,
    PLAY_STALL,
    PLAY_ERROR,
    PLAYBACK_ABANDON,
    SEEK_BACK,
    SEEK_FORWARD,
    TRACK_SKIP,
    LIKE,
    UNLIKE,
    DISLIKE,
    UNDISLIKE,
    SECTION_MARK,
    SECTION_UNMARK,
    PLAYLIST_ADD,
    PLAYLIST_REMOVE,
    COMMENT_ADD,
    COMMENT_DELETE,
    SEARCH

}
