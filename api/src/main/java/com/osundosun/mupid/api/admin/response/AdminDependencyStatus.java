package com.osundosun.mupid.api.admin.response;

/**
 * 외부 저장소 한 곳의 상태.
 *
 * `/api/actuator/health` 는 `show-details: never` 라 `{"status":"UP"}` 밖에 주지 않는다.
 * 관리자는 어디가 죽었는지 알아야 하므로 여기서 하나씩 찔러 본다.
 */
public record AdminDependencyStatus(

    String name,

    /**
     * UP / DOWN / WARN
     *
     * WARN 은 붙어는 있지만 기준을 넘은 상태다 — 지금은 ClickHouse 구간 동기화 한 줄이 쓴다
     * (미반영이 밀렸거나 대조가 어긋났을 때). 화면은 UP 이 아니면 전부 빨갛게 칠한다.
     */
    String status,

    /**
     * 접속 대상. 비밀번호는 들어가지 않는다.
     */
    String target,

    /**
     * 응답에 걸린 시간(ms). 실패하면 -1.
     */
    long latency,

    /**
     * 실패했다면 사유. 성공이면 null.
     */
    String detail

) {
}
