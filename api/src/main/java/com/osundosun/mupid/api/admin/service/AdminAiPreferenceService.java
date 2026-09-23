package com.osundosun.mupid.api.admin.service;

import com.osundosun.mupid.api.admin.dto.AdminAiPreferenceRange;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceAggregateResponse;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceResponse;

/**
 * AI 추천곡을 사람들이 실제로 듣는가 — "AI 선호도" 의 추이 (S15P21A204-427).
 *
 * <h2>왜 ClickHouse 콘솔로 대신할 수 없는가</h2>
 *
 * <p>콘솔로도 같은 숫자는 뽑을 수 있다. 그러나 정의(재생을 어느 시간에 넣는가, 30초 문턱,
 * {@code rec_id} 가 없는 "추천" 재생을 빼는 것)를 매번 손으로 옮겨 적어야 하고, 한 글자만
 * 달라도 다른 숫자가 나와 지난주와 비교할 수 없다. 정의를 코드 한 곳에 두고 배치가 그것으로
 * 쌓은 값을 그리는 것이 이 화면이다.
 */
public interface AdminAiPreferenceService {

    /**
     * 쌓아 둔 시간별 집계를 그래프 모양으로 준다. 원본은 훑지 않는다.
     *
     * @throws com.osundosun.mupid.api.common.exception.BusinessException
     *         {@code MUPID_ANALYTICS_UNAVAILABLE} — ClickHouse 를 못 읽을 때
     */
    AdminAiPreferenceResponse preference(AdminAiPreferenceRange range);

    /**
     * 창을 원본에서 <b>다시 집계</b>한다. 표를 처음 만든 뒤 지난 기간을 채우거나, 배치가
     * 오래 멈췄던 구간을 메울 때 쓴다. 몇 번을 불러도 같은 값이 된다.
     *
     * @throws com.osundosun.mupid.api.common.exception.BusinessException
     *         {@code MUPID_ANALYTICS_UNAVAILABLE} — ClickHouse 를 못 읽거나 못 쓸 때
     */
    AdminAiPreferenceAggregateResponse aggregate(AdminAiPreferenceRange range);

}
