package com.osundosun.mupid.api.analytics.dto;

/**
 * 하루치 재생 행동 요약. MuBTI C축(완주형/스킵형)과 D축의 반복 강도가 이걸 읽는다.
 *
 * `PLAYBACK_ABANDON`(앱이 닫혀 끊김)과 `PLAY_ERROR` 는 세지 않는다. 의도한 스킵이
 * 아닌 것을 스킵으로 세면 안 된다는 것이 domain/listening-events.md 의 규칙이다.
 */
public record DailyPlaySummary(

    /** 자연 종료. 스킵과 구분된다. */
    long ends,

    /** 곡 넘김. `skip_trigger` 는 보지 않는다 — 어떻게 넘겼든 넘긴 것은 같다. */
    long skips,

    long starts,

    /** 곡 안에서 앞으로 감기. 곡을 넘기지 않고도 건너뛰는 행동이다. */
    long seekForwards,

    /** 되감아 다시 듣기. 지금은 응답에만 싣고 판정에는 쓰지 않는다. */
    long seekBacks,

    /** 재생 1회를 묶는 `play_id` 의 개수. */
    long plays

) {

    public static final DailyPlaySummary EMPTY = new DailyPlaySummary(0, 0, 0, 0, 0, 0);

}
