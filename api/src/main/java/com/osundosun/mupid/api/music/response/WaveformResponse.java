package com.osundosun.mupid.api.music.response;

import java.util.List;

/**
 * 한 곡의 파형.
 *
 * <p>`values` 는 0~1 이고 시간 오름차순이다. `sample_count` 를 함께 주는 것은
 * 받는 쪽이 길이를 가정하지 않게 하려는 것이다 — 칸 수는 배치 설정에 따라 바뀔
 * 수 있고, 화면은 그리는 막대 수에 맞춰 다시 표본한다.
 *
 * <p>파형이 없는 곡은 빈 배열이 아니라 <b>404</b> 다. 가사와 다르다 — 가사는
 * 원래 없는 곡이 흔해서 빈 목록이 정상이지만, 파형이 없다는 것은 "이 곡은 아직
 * 배치가 닿지 않았다" 는 뜻이라 화면이 <b>다른 경로(기기 계산)로 갈아타야</b> 한다.
 * 빈 배열로 주면 그 분기를 만들 수 없다.
 */
public record WaveformResponse(

    int musicId,

    /** `values` 의 길이. 편의 값이다. */
    int sampleCount,

    List<Double> values

) {

}
