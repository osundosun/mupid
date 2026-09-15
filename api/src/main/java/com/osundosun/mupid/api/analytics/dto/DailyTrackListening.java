package com.osundosun.mupid.api.analytics.dto;

/**
 * 하루 동안 한 곡을 얼마나 들었는가.
 *
 * `listenedMs` 는 재생기가 돈 시간이 아니라 **귀에 닿은 시간**이다. `PLAY_PROGRESS`
 * 하트비트 하나가 `[from_ms, position_ms)` 를 연속으로 들었다는 뜻이고, seek 하면
 * 프론트가 포인터를 옮기므로 건너뛴 구간은 애초에 들어오지 않는다. 일시정지 중에는
 * 하트비트가 없다.
 *
 * `trackId` 는 MySQL `music_id` 로 본다 — open-questions C5 는 아직 열려 있지만
 * 프론트가 실제로 서버 곡 번호를 실어 보낸다.
 */
public record DailyTrackListening(

    int trackId,

    long listenedMs

) {

}
