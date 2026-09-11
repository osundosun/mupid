package com.osundosun.mupid.api.event.enumerate;

/**
 * 무엇으로 곡을 넘겼는가.
 *
 * 수집하는 것은 여기까지다. EARLY / AFTER_HOOK / LATE 분류는 ClickHouse
 * `v_track_skips` 가 쿼리 시점에 만든다 (decision/0015).
 */
public enum SkipTrigger {

    NEXT_BUTTON,
    OTHER_TRACK

}
