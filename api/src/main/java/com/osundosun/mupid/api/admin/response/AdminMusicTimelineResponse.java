package com.osundosun.mupid.api.admin.response;

import java.util.List;

/**
 * 음악 하나의 가사·구간을 **시간축 위에 가로로** 그리기 위한 응답.
 *
 * <p>S15P21A204-380-B. 요구는 세 가지다 — ① 음악별로 가사/구간을 조회하고 ② 가로로
 * 시간순으로 배치하며 ③ 내용을 수정한다. 이 응답이 ①②를 담당하고, ③ 은 각 행의 `_id`
 * 로 기존 관리자 수정 API 를 그대로 부른다 (`AdminTimelineService` 주석 참고).
 *
 * @param musicId   곡 번호
 * @param title     곡명. 화면이 "무엇의 타임라인인가" 를 말할 수 있어야 한다
 * @param axisMaxMs 가로축의 최대값(ms). **행에서 유도하지 않고 DB 에서 집계한다** —
 *                  마지막 구간 뒤에 남은 여백(아웃트로 이후)이 있어야 비율이 곡 길이처럼
 *                  보이고, 행이 잘렸을 때도 축이 줄어들지 않는다
 * @param hasTime   시간 정보가 하나라도 있는가. 거짓이면 화면은 "아직 분석 전" 을 말해야
 *                  한다 — 축 최대값을 0 으로 두고 나누면 전부 0% 로 뭉친다
 * @param bands     밴드(축) 목록. 가사·구간이 각각 한 밴드다
 */
public record AdminMusicTimelineResponse(
    int musicId,
    String title,
    int axisMaxMs,
    boolean hasTime,
    List<AdminTimelineBand> bands
) {

}
