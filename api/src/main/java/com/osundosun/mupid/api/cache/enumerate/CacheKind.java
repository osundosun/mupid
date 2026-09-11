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
    EventId("event:id");

    private final String redisKey;
    
}
