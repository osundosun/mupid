package com.osundosun.mupid.api.analytics.dto;

/**
 * `listen_events` 한 줄을 **화면에 그릴 만큼만** 추린 것 (S15P21A204-430).
 *
 * <p>테이블의 컬럼을 다 담지 않는다. 추천 추적용 세 값(`rec_id`·`rec_type`·`rec_rank`)과
 * `ext` 맵, 댓글 본문(`content`)은 뺐다 — 하루치를 수백 건 실어 보내는 응답이라
 * 안 쓰는 컬럼이 그대로 응답 크기가 된다. 필요해지면 그때 늘린다.
 *
 * <h2>시각을 epoch ms 로 받는 이유</h2>
 *
 * JDBC 드라이버가 {@code Timestamp} 를 어느 시간대로 돌려주는지에 화면의 위치가
 * 좌우되면 안 된다. 숫자에는 시간대가 없다 — {@code toUnixTimestamp64Milli} 로 받아
 * 자정과의 차이를 자바에서 계산한다. {@code ListenAnalyticsRepository} 가 보낼 때
 * 문자열로 못 박는 것과 같은 판단이다.
 *
 * @param eventId     멱등 키(UUID 문자열). 화면이 행을 구분하는 데 쓴다
 * @param occurredMs  발생 시각(epoch ms). <b>전송 시각이 아니다</b>
 * @param eventType   이벤트 종류. 22종 밖의 값이 올 수 있다 — 적재는 되고 해석만 없다
 * @param trackId     대상 곡. 재생 밖의 이벤트(`SEARCH`)는 0
 * @param playId      재생 1회를 묶는 키. 없으면 영(zero) UUID 문자열이다
 * @param entryPath   이 재생의 진입 경로. 없으면 빈 문자열
 * @param positionMs  곡 안의 재생 위치
 * @param fromMs      `SEEK_*` 는 직전 위치, `PLAY_PROGRESS` 는 직전 하트비트 위치
 * @param skipTrigger 스킵을 무엇으로 했는가
 * @param target      `LIKE`·`DISLIKE` 의 대상 (`TRACK`·`SECTION`)
 * @param searchQuery `SEARCH` 의 검색어
 * @param playlistId  `PLAYLIST_*` 의 플레이리스트 번호
 * @param commentId   `COMMENT_*` 의 댓글 번호
 * @param reason      `PLAY_ERROR` 의 사유 코드
 */
public record UserEventRow(
    String eventId,
    long occurredMs,
    String eventType,
    int trackId,
    String playId,
    String entryPath,
    int positionMs,
    int fromMs,
    String skipTrigger,
    String target,
    String searchQuery,
    int playlistId,
    int commentId,
    String reason
) {

}
