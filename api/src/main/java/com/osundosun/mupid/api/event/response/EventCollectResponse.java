package com.osundosun.mupid.api.event.response;

public record EventCollectResponse(

    /**
     * Kafka 로 넘긴 건수
     */
    int accepted,

    /**
     * 이미 받은 event_id 라 버린 건수
     */
    int duplicated,

    /**
     * 값이 규약에 맞지 않아 버린 건수
     */
    int rejected

) {

}
