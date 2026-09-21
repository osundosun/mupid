package com.osundosun.mupid.api.recommendation.service;

/**
 * 추천이 화면에 건 사유 문장을 재생이 들어올 때까지 들고 있는 자리.
 *
 * <h2>왜 필요한가</h2>
 *
 * 재생 기록(`play_histories.recommendation_reason`)에 사유가 비어 있는 것은 저장 로직이
 * 빠져서가 아니다. {@code HistoryServiceImpl.recordPlay} 는 처음부터 사유를 받아 넣고
 * 있었고, 유일한 호출부도 사유를 넘기고 있었다. 비는 진짜 이유는 <b>넘길 값이 없기
 * 때문</b>이다 — 청취 이벤트에는 {@code entry_path} 만 실리고 사유는 실리지 않는다
 * (`knowledge/domain/listening-events.md` 의 `PLAY_START` 고유 필드는
 * `position_ms` · `rec_id` · `rec_type` · `rec_rank` 뿐이다). 사유는 사용자가 곡을 누르기
 * <b>전에 본 추천 응답</b>에만 있고, 재생 이벤트는 그 응답을 지나고 나서 도착한다.
 *
 * 그래서 그 사이를 잇는 저장소가 필요하다. 상관 키는 이미 있다 — 추천 응답이
 * `recommendation_id`(`AiRecommendToolImpl` 의 `recommendationId`)를 주고, 화면이
 * 재생 이벤트에 `rec_id` 로 되돌려 준다({@code frontend/lib/analytics/tracker.ts} 의
 * `startPlay`). 곡은 `track_id` 로 갈린다. 이 둘을 키로 사유를 적어 두고, 재생이 들어올 때
 * 되짚는다.
 *
 * <h2>왜 MySQL 이 아니라 캐시인가</h2>
 *
 * 사유는 <b>해석이 아니라 그때 화면에 보여 준 문장</b>이라 재현할 수 없다. 추천을 영속하는
 * 표(`daily_recommendation_tracks`)는 하루 한 번 도는 배치 경로에만 있고, 이어 듣기는
 * 수시로 불려 저장되지 않는다. 게다가 배치 사유는 화면이 상관 키를 되돌려주지 않아
 * 되짚을 수조차 없다. 재생 기록은 어차피 이벤트 수집 경로의 <b>부수적 사본</b>이라
 * ([decision/0018]), 그 사본에 붙는 부가 정보도 같은 성격의 저장소에 두는 것이 맞다.
 *
 * <h2>없어도 되는 정보로 다룬다</h2>
 *
 * 값이 없거나 조회가 실패해도 <b>재생 기록 자체는 남는다.</b> 선곡 주체(`selector`)는
 * 진입 경로만으로 정해지므로 사유와 무관하다. 사유는 "있으면 좋은" 칸이고, 그것 때문에
 * 수집이 실패하거나 재생 이력이 사라지면 안 된다.
 */
public interface RecommendationReasonCache {

    /**
     * 추천 한 건이 화면에 건 사유를 기억한다.
     *
     * 실패해도 던지지 않는다. 추천 응답은 사유를 기억하지 못했다고 실패할 일이 아니다.
     *
     * @param recommendationId 추천 묶음 번호. 화면이 `rec_id` 로 되돌려 주는 값
     * @param musicId          추천된 곡 번호. 화면이 `track_id` 로 되돌려 주는 값
     * @param reason           화면에 보여 준 문장. 빈 값이면 아무것도 하지 않는다
     */
    void remember(long recommendationId, int musicId, String reason);

    /**
     * 재생이 되돌려준 상관 키로 사유를 찾는다.
     *
     * @param recommendationId 이벤트의 `rec_id`. 추천 경로가 아니면 빈 문자열
     * @param musicId          이벤트의 `track_id`
     * @return 기억해 둔 문장. 모르면 null
     */
    String find(String recommendationId, int musicId);

}
