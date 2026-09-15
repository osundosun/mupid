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
    ClickHouseConsole("admin:clickhouse:console");

    private final String redisKey;
    
}
