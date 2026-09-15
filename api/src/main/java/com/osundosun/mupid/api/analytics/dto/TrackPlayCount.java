package com.osundosun.mupid.api.analytics.dto;

/**
 * 곡별 <b>재생 시도</b> 횟수. `PLAY_START` 를 센다. 두 군데서 쓴다.
 *
 * <ul>
 *   <li><b>하루치, 한 사용자</b> — MuBTI D축(국밥/개척)의 반복 강도</li>
 *   <li><b>전체 기간, 전 사용자</b> — MuBTI B축(트렌드/매니아)의 카탈로그 대중성 기준선</li>
 * </ul>
 *
 * 청취 시간(`DailyTrackListening`)과 따로 두는 이유가 있다. 청취 시간은
 * `PLAY_PROGRESS` 하트비트에서 나오는데 하트비트는 5초 간격이라, <b>그 전에 스킵한
 * 곡은 청취 시간이 0 이고 집계에 아예 나타나지 않는다.</b> "빠르게 넘기며 디깅한다" 가
 * 정확히 그 모양이라, 시간으로만 재면 가장 활발히 탐색한 사람이 아무것도 안 한 것처럼
 * 보인다.
 */
public record TrackPlayCount(

    int trackId,

    /** 튼 횟수. 스킵으로 끝난 재생도 포함한다. */
    long plays

) {

}
