package com.osundosun.mupid.api.event.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이벤트 한 건. 필드 이름은 ClickHouse `listen_events` 컬럼과 맞춘다
 * (`spring.jackson.property-naming-strategy: SNAKE_CASE` 로 snake_case JSON 과 매핑된다).
 *
 * `user_id` 는 받지 않는다. 액세스 토큰에서 서버가 확정한다 — 클라이언트가 보낸 값을
 * 믿으면 남의 취향 데이터를 오염시킬 수 있다.
 *
 * 값 집합이 있는 필드(`event_type`, `entry_path`, `skip_trigger`, `target`, `rec_type`)는
 * enum 이 아니라 String 으로 받는다. enum 으로 받으면 잘못된 값 하나에 Jackson 이
 * 역직렬화 단계에서 실패해 **배치 전체가** 거부되기 때문이다. 검증은 서비스가 건별로 한다.
 */
public record EventItemRequest(

    @NotBlank(message = "이벤트 ID가 입력되지 않았습니다.")
    String eventId,

    @NotBlank(message = "이벤트 종류가 입력되지 않았습니다.")
    String eventType,

    @NotBlank(message = "이벤트 발생 일시가 입력되지 않았습니다.")
    String occurredAt,

    Integer trackId,

    String playId,

    String entryPath,

    String recId,

    String recType,

    Integer recRank,

    Long positionMs,

    Long fromMs,

    Long startMs,

    Long endMs,

    String target,

    String skipTrigger,

    @Size(max = 100, message = "reason 은 100자를 넘을 수 없습니다.")
    String reason,

    Integer playlistId,

    Integer commentId,

    @Size(max = 1000, message = "댓글은 1000자를 넘을 수 없습니다.")
    String content,

    @Size(max = 500, message = "검색어는 500자를 넘을 수 없습니다.")
    String searchQuery,

    /**
     * 스키마 변경 없이 추가 정보를 넣는 자리. 규모가 커지면 실컬럼으로 승격한다.
     */
    @Size(max = 10, message = "ext 는 10개를 넘을 수 없습니다.")
    Map<String, String> ext

) {

}
