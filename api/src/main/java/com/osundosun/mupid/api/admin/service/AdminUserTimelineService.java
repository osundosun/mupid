package com.osundosun.mupid.api.admin.service;

import com.osundosun.mupid.api.admin.dto.AdminUserTimelineRange;
import com.osundosun.mupid.api.admin.response.AdminUserTimelineResponse;

/**
 * 사용자 한 명의 **행동 데이터를 날짜에 따라** 조회한다 (S15P21A204-430).
 *
 * <h2>왜 기존 관리자 조회로는 안 되는가</h2>
 *
 * 관리자 화면의 목록 조회({@link AdminResourceService#list})는 <b>MySQL 의 엔티티</b>를
 * 평평하게 준다. 그런데 요구가 말하는 "행동 데이터" 는 MySQL 에 없다 — 재생·스킵·되감기·
 * 좋아요는 전부 ClickHouse 의 {@code listen_events} 한 스트림이고, 그 표는 엔티티가 아니라
 * 관리 자원으로 올라오지도 않는다.
 *
 * <p>가사·구간 타임라인({@link AdminTimelineService})도 쓸 수 없다. 그쪽은
 * <b>곡 안의 ms</b> 를 축으로 삼아 관리 자원의 행을 늘어놓는 것이고, 축을 데이터에서
 * 집계해 정한다. 여기 축은 <b>하루</b>이고 언제나 24시간이다.
 *
 * <h2>ClickHouse 콘솔로 대신할 수 없는 이유</h2>
 *
 * 관리자 화면에는 이미 ClickHouse 콘솔이 있어서 쿼리를 칠 수는 있다. 그러나 그 화면이
 * 주는 것은 <b>표</b>다 — 시각이 세로로 늘어선 목록에서는 "저녁에 몰아 들었다", "스킵이
 * 한 구간에 몰렸다" 같은 모양이 보이지 않는다. 요구는 타임라인이고, 그것은 시간 위의
 * 위치가 눈에 보여야 한다는 뜻이다. 곡 번호를 제목으로 옮기는 일도 콘솔에서는 안 된다
 * (ClickHouse 에 곡 메타데이터가 없다).
 */
public interface AdminUserTimelineService {

    /**
     * 회원 하나의 행동 타임라인.
     *
     * <p>활동이 하나도 없어도 <b>404 가 아니다</b> — 날짜 막대가 0 으로 채워져 오고
     * {@code has_activity} 가 거짓이다. 계정 자체가 없을 때만 404 다. 탈퇴한 계정은
     * 조회된다: 이벤트가 남아 있고, 그 기록을 보려는 것이 이 화면이다.
     *
     * @param userId 회원 번호
     * @param range  검증된 조회 창. {@code date} 가 비어 있으면 <b>활동이 있었던 가장
     *               최근 날</b>을 고른다 — 날짜 목록을 받아 봐야 알 수 있는 값이라
     *               {@link AdminUserTimelineRange} 가 정하지 않는다
     */
    AdminUserTimelineResponse timeline(int userId, AdminUserTimelineRange range);

}
