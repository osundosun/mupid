package com.osundosun.mupid.api.analytics.dto;

/**
 * 재생 한 번 — {@code play_id} 로 묶은 이벤트 무리 (S15P21A204-430).
 *
 * <h2>왜 이것을 따로 만드는가</h2>
 *
 * `PLAY_PROGRESS` 는 <b>5초 하트비트</b>다. 한 곡을 3분 들으면 그것만 서른여섯 건이고,
 * 하루 치를 점으로 늘어놓으면 시간축이 그 점으로 덮여 나머지 행동(좋아요·스킵·검색)이
 * 보이지 않는다. 그래서 하트비트는 개별 항목으로 그리지 않고 <b>재생 한 번을 하나의
 * 막대</b>로 묶는다 — 타임라인에서 "언제부터 언제까지 무엇을 들었는가" 가 곧 이 막대다.
 *
 * <p>버리는 것이 아니다. 하트비트는 {@link #listenedMs} 와 막대의 길이로 남는다.
 *
 * @param playId     재생 1회를 묶는 키 (UUID 문자열)
 * @param startedMs  이 재생의 첫 이벤트 시각(epoch ms)
 * @param endedMs    마지막 이벤트 시각(epoch ms). 하루를 넘겨 이어진 재생은 호출부가
 *                   그날의 끝으로 자른다
 * @param trackId    곡 번호
 * @param entryPath  진입 경로 (`RECOMMEND`·`SEARCH`·`PLAYLIST`)
 * @param events     이 재생에 속한 이벤트 수 (하트비트 포함)
 * @param listenedMs 실제로 들은 시간. 막대 길이(경과 시간)와 <b>다르다</b> — 일시정지와
 *                   앞으로감기가 그 차이다
 */
public record UserPlaySession(
    String playId,
    long startedMs,
    long endedMs,
    int trackId,
    String entryPath,
    long events,
    long listenedMs
) {

}
