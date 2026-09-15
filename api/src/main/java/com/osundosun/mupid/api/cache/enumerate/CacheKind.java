package com.osundosun.mupid.api.cache.enumerate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CacheKind {

    BlockToken("block:token"),

    /**
     * 이미 받은 청취 이벤트. 재시도로 같은 event_id 가 다시 와도 Kafka 에 두 번 넣지 않는다.
     */
    EventId("event:id"),

    /**
     * 관리자 ClickHouse 콘솔의 열람권. 값은 연 사람의 이메일이다.
     *
     * JWT 로 만들지 않는다. iframe 은 로그아웃했다고 알려 주지 않으므로, 서버가
     * 지워서 바로 끊을 수 있어야 한다.
     */
    ClickHouseConsole("admin:clickhouse:console"),

    /**
     * 그날 이전에 들어본 곡 번호 (취향 리포트 D축).
     *
     * 90일치를 훑는 리포트에서 가장 무거운 질의인데 **그날 안에는 값이 변하지 않는다.**
     * 오늘치 집계와 수명을 나누려고 따로 둔다.
     */
    ReportHeardTracks("report:heard"),

    /**
     * 직전 MuBTI 판정 네 글자.
     *
     * 점수가 0 근처일 때 조회할 때마다 글자가 뒤집히는 것을 막는다 (이력 현상).
     * 저장하는 것은 결론뿐이고 점수는 매번 다시 계산한다 — decision/0015.
     */
    ReportVerdict("report:verdict"),

    /**
     * 오늘치 리포트를 MySQL 에 스냅샷한 표시. 있으면 이번 조회에서는 쓰지 않는다.
     * 조회마다 쓰면 읽기 경로에 쓰기 폭풍이 붙는다.
     */
    ReportSnapshot("report:snapshot"),

    /**
     * 자정 배치 선점. API 를 여러 벌 띄워도 하루치 확정 저장은 한 번만 돈다.
     */
    ReportBatch("report:batch");

    private final String redisKey;
    
}
