package com.osundosun.mupid.api.admin.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 사용자 한 명의 **행동 데이터를 날짜에 따라** 그리기 위한 응답 (S15P21A204-430).
 *
 * <p>두 층이다. 위는 <b>어느 날에 무엇이 있었는가</b>({@link #days} — 창 전체의 날짜
 * 막대), 아래는 <b>그 하루 안에서 언제 무엇을 했는가</b>({@link #bands} — 24시간 시간축).
 * 하나만으로는 요구가 안 된다: 날짜 막대만 있으면 "그날 무엇을 했는지" 가 없고, 하루
 * 시간축만 있으면 <b>어느 날을 봐야 하는지 알 방법이 없다.</b>
 *
 * <h2>가사·구간 타임라인과 무엇이 다른가</h2>
 *
 * 축이 다르다. 그쪽은 <b>곡 안의 ms</b> 이고 축 최대값이 곡마다 다르다. 이쪽은
 * <b>하루</b>라서 언제나 24시간이다 — 축을 그날 데이터에서 유도하지 않는다. 유도하면
 * 새벽 두 시에만 쓴 날과 온종일 쓴 날의 눈금이 달라져 <b>날짜를 바꿀 때마다 같은 위치가
 * 다른 시각을 뜻하게</b> 된다.
 *
 * <p>원본도 다르다. 그쪽은 MySQL 의 관리 자원이라 행을 그 자리에서 고칠 수 있지만,
 * 이쪽은 ClickHouse 의 이벤트 스트림이다. <b>이 화면에는 편집이 없다</b> — 원본은 고치지
 * 않고 쌓기만 한다는 수집 설계 그대로다.
 *
 * @param userId         회원 번호
 * @param email          이메일. "누구의 타임라인인가" 를 화면이 말할 수 있어야 한다
 * @param nickname       닉네임
 * @param status         회원 상태. 탈퇴한 계정도 <b>이벤트는 남아 있어</b> 조회된다 —
 *                       상태를 함께 보내지 않으면 보는 사람이 활동 중인 회원으로 읽는다
 * @param from           창의 시작일. 포함
 * @param to             창의 종료일. 포함
 * @param date           시간축에 펼친 하루. 요청이 날짜를 지정하지 않으면 <b>창 안에서
 *                       활동이 있었던 가장 최근 날</b>이고, 활동이 하나도 없으면
 *                       {@code to} 다
 * @param timeZone       날짜의 기준 시간대(`Asia/Seoul`). 보는 사람의 브라우저 시간대와
 *                       다를 수 있으므로 <b>값으로 말해 둔다</b>
 * @param hasActivity    창 전체에 이벤트가 하나라도 있는가. 거짓이면 화면은 "기록 없음" 을
 *                       말해야 한다 — 빈 막대와 빈 시간축만 그리면 불러오기에 실패한
 *                       것처럼 보인다
 * @param days           날짜 막대. <b>기록이 없는 날도 0 으로 들어간다</b>
 * @param dayEvents      선택한 날의 전체 이벤트 수. 하트비트를 포함하므로 밴드의
 *                       {@code total} 합과 다르다 — 그 차이가 재생 세션으로 접힌 양이다
 * @param dayListenedMs  선택한 날의 실제 청취 시간
 * @param heartbeatEvents 선택한 날의 5초 하트비트 수. <b>시간축에 점으로 그리지 않은
 *                       건수</b>다. 감춘 것이 아니라 재생 세션 막대로 접었다는 사실을
 *                       화면이 말할 수 있도록 수를 보낸다
 * @param bands          밴드 목록. 재생 세션·재생 제어·탐색·피드백, 그리고 모르는 종류가
 *                       있었을 때만 기타
 */
public record AdminUserTimelineResponse(
    int userId,
    String email,
    String nickname,
    String status,
    LocalDate from,
    LocalDate to,
    LocalDate date,
    String timeZone,
    boolean hasActivity,
    List<AdminUserActivityDay> days,
    long dayEvents,
    long dayListenedMs,
    long heartbeatEvents,
    List<AdminUserEventBand> bands
) {

    /**
     * 시간축의 최대값(ms) — 하루.
     *
     * <p>응답에 싣지 않는다. <b>날마다 같은 값</b>이라 보낼 이유가 없고, 보내면 화면이
     * 그것을 축의 근거로 삼아 "언젠가 달라질 수 있는 값" 처럼 다루게 된다. 화면도 하루가
     * 24시간이라는 것을 알고 있다.
     */
    public static final int DAY_MS = 24 * 60 * 60 * 1000;

}
