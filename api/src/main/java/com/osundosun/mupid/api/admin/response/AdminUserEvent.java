package com.osundosun.mupid.api.admin.response;

/**
 * 타임라인 위의 항목 하나 — 이벤트 한 건, 또는 재생 한 번 (S15P21A204-430).
 *
 * <h2>왜 하루 안의 ms 로 보내는가</h2>
 *
 * 화면은 항목을 {@code left: N%} 로 놓는다. 그 비율을 만들려면 <b>하루라는 축 위의
 * 좌표</b>가 필요하지, 시각 문자열이 필요한 것이 아니다. 문자열을 보내면 화면이 그것을
 * 다시 파싱해 좌표로 바꾸게 되는데, 그 파싱이 브라우저 시간대를 타서 <b>항목이 몇 시간씩
 * 밀린다</b>. 가사·구간 타임라인이 곡 안의 ms 를 그대로 보내는 것과 같은 규칙이다.
 *
 * <p>읽을 시각({@link #at})은 <b>서버가 만든 문자열</b>을 함께 보낸다. 같은 값을 두 번
 * 보내는 셈이지만, 화면이 ms 에서 시:분:초를 다시 계산하면 그 계산이 또 시간대를 탄다.
 *
 * @param id         항목 식별자. 이벤트는 {@code event_id}, 재생 세션은 {@code play_id} 다.
 *                   화면이 행을 구분하는 데만 쓴다 — <b>이 화면에는 편집이 없다</b>
 * @param eventType  이벤트 종류 원래 값 (`PLAY_START`). 재생 세션은 {@code PLAY_SESSION}
 *                   이라는 <b>합성 값</b>이다 — 그런 이벤트는 존재하지 않는다
 * @param label      한글 이름. 표에 없는 종류는 {@code eventType} 그대로다
 * @param atMs       자정부터의 ms. 0 ~ 86,400,000
 * @param endMs      끝. 재생 세션에만 있고 나머지는 {@code null} 이다 — 점에는 길이가 없다
 * @param at         `HH:mm:ss` 표기
 * @param trackId    대상 곡. 재생 밖의 이벤트(`SEARCH`)는 {@code null}
 * @param trackTitle 곡 제목. 곡이 지워졌거나 카탈로그에 없으면 {@code null} 이다 —
 *                   <b>번호는 남긴다.</b> 둘 다 지우면 무엇에 대한 행동인지 사라진다
 * @param playId     이 항목이 속한 재생. 없으면 {@code null} (영 UUID 는 보내지 않는다)
 * @param entryPath  진입 경로. 없으면 {@code null}
 * @param positionMs 곡 안의 재생 위치. 없으면 {@code null}
 * @param detail     그 종류에만 있는 값 한 줄 — 검색어·스킵 방식·대상·사유 등.
 *                   없으면 {@code null}
 * @param events     이 재생에 속한 이벤트 수. 재생 세션에만 있다
 * @param listenedMs 이 재생에서 실제로 들은 시간. 재생 세션에만 있고, <b>막대 길이와
 *                   다르다</b> — 일시정지와 앞으로감기가 그 차이다
 */
public record AdminUserEvent(
    String id,
    String eventType,
    String label,
    int atMs,
    Integer endMs,
    String at,
    Integer trackId,
    String trackTitle,
    String playId,
    String entryPath,
    Integer positionMs,
    String detail,
    Long events,
    Long listenedMs
) {

    /**
     * 재생 세션의 {@code event_type} 자리에 넣는 값.
     *
     * <p>이벤트 22종에 이런 것은 없다. 세션은 {@code play_id} 로 <b>묶어서 만든 것</b>이고,
     * 그 사실이 응답에 드러나야 한다 — 빈 값으로 두면 화면이 "종류를 모르는 이벤트" 로
     * 그리고, 실제 종류 이름을 하나 골라 넣으면 그 이벤트가 그 시각에 있었다는 거짓이 된다.
     */
    public static final String SESSION_TYPE = "PLAY_SESSION";

}
