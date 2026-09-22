package com.osundosun.mupid.api.admin.dto;

/**
 * 사용자 행동 타임라인의 **밴드 한 줄** (S15P21A204-430).
 *
 * <h2>왜 이벤트 종류마다 밴드를 만들지 않는가</h2>
 *
 * 이벤트가 22종이다. 종류마다 한 줄이면 화면이 스물두 줄이 되고, 하루에 실제로 일어나는
 * 것은 그중 대여섯 종이라 <b>빈 줄이 열대여섯 줄</b> 깔린다. 읽어야 할 것이 어디 있는지
 * 보이지 않는다.
 *
 * <p>대신 <b>보는 사람이 묻는 질문</b>으로 묶는다 — "무엇을 들었나"(세션), "재생을 어떻게
 * 다뤘나"(제어), "돌아다녔나"(탐색), "표시를 남겼나"(피드백). 네 줄이면 하루가 한눈에
 * 들어온다.
 *
 * <h2>{@link #OTHER} 가 있는 이유</h2>
 *
 * 수집기는 <b>아는 22종만</b> 받지만 그 목록은 늘어난다. 새 종류가 들어왔는데 여기 표가
 * 아직 모르면 그 이벤트는 갈 곳이 없다 — 조용히 사라지면 화면은 "그날 그 행동이 없었다"
 * 고 거짓말을 한다. 모르는 것은 {@code OTHER} 로 모아 <b>원래 이름 그대로</b> 보여 준다.
 */
public enum AdminUserEventGroup {

    /**
     * 재생 한 번을 통째로 묶은 막대. {@code play_id} 단위다.
     *
     * <p>이 밴드만 {@link AdminTimelineKind#RANGE} 다 — 나머지는 한 시점에 일어난 점이고,
     * 재생만 <b>길이를 갖는다</b>. 그 길이가 "무엇을 얼마나 들었는가" 라서 타임라인의
     * 등뼈가 된다.
     */
    SESSION("재생 세션", AdminTimelineKind.RANGE),

    /** 재생을 시작·멈춤·끝냄. 끊긴 재생(오류·버퍼링·앱 종료)도 여기다. */
    PLAYBACK("재생 제어", AdminTimelineKind.POINT),

    /** 곡 안팎을 돌아다닌 흔적 — 되감기·앞으로감기·스킵·검색. */
    NAVIGATION("탐색·스킵", AdminTimelineKind.POINT),

    /** 사용자가 남긴 표시 — 좋아요·관심없음·구간 지정·플레이리스트·댓글. */
    FEEDBACK("피드백", AdminTimelineKind.POINT),

    /** 표가 모르는 이벤트. 조용히 버리지 않는다. */
    OTHER("기타", AdminTimelineKind.POINT);

    private final String label;
    private final AdminTimelineKind kind;

    AdminUserEventGroup(String label, AdminTimelineKind kind) {
        this.label = label;
        this.kind = kind;
    }

    public String label() {
        return label;
    }

    public AdminTimelineKind kind() {
        return kind;
    }

}
