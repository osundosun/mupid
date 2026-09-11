package com.osundosun.mupid.api.event.dto;

import java.util.Map;

/**
 * Kafka 토픽 `mupid.listen.events` 로 나가는 한 줄. ClickHouse `kafka_listen_events`
 * 컬럼과 1:1 이고, `JSONEachRow` 로 파싱된다.
 *
 * **null 을 담지 않는다.** `position_ms` 같은 컬럼은 Nullable 이 아니라서 JSON 에
 * `null` 이 오면 그 줄이 파싱에 실패한다. 게다가 Kafka 엔진에 `kafka_skip_broken_messages`
 * 가 걸려 있어 **조용히 버려진다** — 오류도 로그도 남지 않는다. 그래서 값이 없는 필드는
 * 빈 문자열이나 0 으로 채운다.
 */
public record ListenEventMessage(

    String eventId,

    String eventType,

    int userId,

    int trackId,

    /**
     * `yyyy-MM-dd HH:mm:ss.SSS` (Asia/Seoul). ClickHouse 가
     * `parseDateTime64BestEffort(..., 3, 'Asia/Seoul')` 로 읽는다.
     */
    String occurredAt,

    String playId,

    String entryPath,

    String recId,

    String recType,

    int recRank,

    long positionMs,

    long fromMs,

    long startMs,

    long endMs,

    String target,

    String skipTrigger,

    String reason,

    int playlistId,

    int commentId,

    String content,

    String searchQuery,

    Map<String, String> ext

) {

}
