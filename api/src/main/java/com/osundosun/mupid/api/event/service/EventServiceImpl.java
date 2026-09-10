package com.osundosun.mupid.api.event.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.event.dto.ListenEventMessage;
import com.osundosun.mupid.api.event.enumerate.EntryPath;
import com.osundosun.mupid.api.event.enumerate.EventType;
import com.osundosun.mupid.api.event.enumerate.FeedbackTarget;
import com.osundosun.mupid.api.event.enumerate.RecType;
import com.osundosun.mupid.api.event.enumerate.SkipTrigger;
import com.osundosun.mupid.api.event.error.EventErrorCode;
import com.osundosun.mupid.api.event.properties.EventProperties;
import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.request.EventItemRequest;
import com.osundosun.mupid.api.event.response.EventCollectResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 청취 이벤트 수집기. 프론트가 보낸 배치를 검증·정규화해서 Kafka 로 넘긴다.
 * 이후 경로(Kafka → ClickHouse)는 [decision/0005], 이 구조의 배경은 [decision/0014].
 *
 * 트랜잭션이 없다. MySQL 을 건드리지 않고 Redis 와 Kafka 만 다룬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventServiceImpl implements EventService {

    /** ClickHouse 쪽 컬럼이 UInt32 다. 이 범위를 넘는 값은 잘라서 보낸다. */
    private static final long UINT32_MAX = 4294967295L;

    /** UInt8 인 rec_rank 전용. */
    private static final int UINT8_MAX = 255;

    private static final int EXT_MAX_ENTRIES = 10;
    private static final int EXT_MAX_LENGTH = 200;

    /**
     * ClickHouse 가 `parseDateTime64BestEffort(..., 3, 'Asia/Seoul')` 로 읽는 형식.
     * ISO 문자열을 그대로 넘기지 않고 여기서 못 박는다 — best-effort 파서가 오프셋 표기를
     * 어떻게 해석하는지에 서비스 시각이 좌우되면 안 된다.
     */
    private static final DateTimeFormatter CLICKHOUSE_TIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Seoul"));

    private final EventProperties props;
    private final CacheService cacheService;
    private final KafkaTemplate<String, String> eventKafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public EventCollectResponse collectEvents(Integer userId, EventCollectRequest dto) {
        if (dto.events().size() > props.maxBatchSize()) {
            throw new BusinessException(EventErrorCode.EVENT_BATCH_TOO_LARGE);
        }

        Instant now = Instant.now();
        int accepted = 0;
        int duplicated = 0;
        int rejected = 0;

        for (EventItemRequest item : dto.events()) {
            // 한 건이 규약에 어긋나도 배치 전체를 거부하지 않는다. 이벤트 하나 때문에
            // 그 사용자의 청취 기록이 통째로 사라지는 쪽이 더 나쁘다.
            Optional<ListenEventMessage> message = normalize(userId, item, now);
            if (message.isEmpty()) {
                rejected++;
                continue;
            }

            Optional<String> payload = serialize(message.get());
            if (payload.isEmpty()) {
                rejected++;
                continue;
            }

            // 멱등 처리는 직렬화까지 끝난 뒤에 한다. 먼저 잡아 두고 실패하면 클라이언트가
            // 같은 event_id 로 재시도해도 "이미 받음" 으로 버려져 이벤트가 영영 사라진다.
            String eventId = message.get().eventId();
            if (!cacheService.setIfAbsent(eventId, CacheKind.EventId, props.dedupTtl())) {
                duplicated++;
                continue;
            }

            publish(message.get(), payload.get());
            accepted++;
        }

        return new EventCollectResponse(accepted, duplicated, rejected);
    }

    /**
     * Kafka 에 넘긴다. 응답을 기다리지 않는다 — 하트비트가 초당 여러 건 들어오는 경로라
     * 브로커 왕복을 요청 스레드에 묶으면 안 된다.
     */
    private void publish(ListenEventMessage message, String payload) {
        try {
            eventKafkaTemplate
                .send(props.kafkaTopic(), Integer.toString(message.userId()), payload)
                .whenComplete((result, error) -> {
                    if (error == null) {
                        return;
                    }
                    releaseEventId(message.eventId());
                    log.error(
                        "청취 이벤트 발행 실패 event_id={} event_type={}",
                        message.eventId(), message.eventType(), error
                    );
                });
        } catch (RuntimeException e) {
            // 브로커 메타데이터를 못 받으면 send 가 그 자리에서 던진다.
            releaseEventId(message.eventId());
            log.error("청취 이벤트 발행 실패 event_id={}", message.eventId(), e);
            throw new BusinessException(EventErrorCode.EVENT_PUBLISH_FAILED);
        }
    }

    /**
     * 발행하지 못한 event_id 를 놓아준다. 이걸 빠뜨리면 재시도가 중복으로 걸러진다.
     */
    private void releaseEventId(String eventId) {
        try {
            cacheService.delete(eventId, CacheKind.EventId);
        } catch (RuntimeException e) {
            log.warn("멱등 키 해제 실패 event_id={}", eventId, e);
        }
    }

    private Optional<String> serialize(ListenEventMessage message) {
        try {
            return Optional.of(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.warn("청취 이벤트 직렬화 실패 event_id={}", message.eventId(), e);
            return Optional.empty();
        }
    }

    /**
     * 검증과 정규화. 되돌릴 수 없는 원본 테이블에 닿기 전 마지막 관문이다.
     * 규약에 맞지 않으면 비어 있는 값을 돌려주고 호출부가 그 건만 버린다.
     */
    private Optional<ListenEventMessage> normalize(
        Integer userId,
        EventItemRequest item,
        Instant now
    ) {
        // event_id 는 멱등 키이자 ClickHouse 의 중복 판정 키다. UUID 가 아니면 둘 다 깨진다.
        Optional<String> eventId = uuid(item.eventId());
        if (eventId.isEmpty()) {
            return Optional.empty();
        }

        Optional<EventType> eventType = parse(EventType.class, item.eventType());
        if (eventType.isEmpty()) {
            return Optional.empty();
        }

        Optional<Instant> occurredAt = parseOccurredAt(item.occurredAt(), now);
        if (occurredAt.isEmpty()) {
            return Optional.empty();
        }

        // play_id 는 없을 수 있다(SEARCH 등). 있는데 형식이 틀리면 그건 버그다.
        String playId = "";
        if (hasText(item.playId())) {
            Optional<String> parsed = uuid(item.playId());
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            playId = parsed.get();
        }

        return Optional.of(new ListenEventMessage(
            eventId.get(),
            eventType.get().name(),
            userId, /* 본문이 아니라 액세스 토큰에서 온 값이다 */
            clampToInt(item.trackId()),
            CLICKHOUSE_TIME.format(occurredAt.get()),
            playId,
            name(parse(EntryPath.class, item.entryPath())),
            text(item.recId()),
            name(parse(RecType.class, item.recType())),
            clamp(item.recRank(), UINT8_MAX),
            clampMs(item.positionMs()),
            clampMs(item.fromMs()),
            clampMs(item.startMs()),
            clampMs(item.endMs()),
            name(parse(FeedbackTarget.class, item.target())),
            name(parse(SkipTrigger.class, item.skipTrigger())),
            text(item.reason()),
            clampToInt(item.playlistId()),
            clampToInt(item.commentId()),
            text(item.content()),
            text(item.searchQuery()),
            ext(item.ext())
        ));
    }

    /**
     * 발생 시각은 클라이언트를 믿되 창을 벗어난 값은 잘라 넣는다. 거부하지 않는 이유는
     * 오프라인 뒤 일괄 전송을 살리기 위해서다 — 시계가 틀어진 기기의 이벤트라도
     * "언제쯤" 은 남는 편이 통째로 버리는 것보다 낫다.
     */
    private Optional<Instant> parseOccurredAt(String value, Instant now) {
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException offsetFailed) {
            try {
                parsed = Instant.parse(value);
            } catch (RuntimeException instantFailed) {
                return Optional.empty();
            }
        }

        Instant oldest = now.minus(props.occurredAtMaxPast());
        Instant newest = now.plus(props.occurredAtMaxFuture());
        if (parsed.isBefore(oldest)) {
            return Optional.of(oldest);
        }
        if (parsed.isAfter(newest)) {
            return Optional.of(newest);
        }
        return Optional.of(parsed);
    }

    private Optional<String> uuid(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.trim()).toString());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 값 집합을 벗어난 문자열은 이벤트를 버리지 않고 그 칸만 비운다. 진입 경로 하나가
     * 오타라고 재생 기록 전체를 버릴 이유는 없다.
     */
    private <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.trim()));
        } catch (IllegalArgumentException e) {
            log.debug("알 수 없는 {} 값: {}", type.getSimpleName(), value);
            return Optional.empty();
        }
    }

    private Map<String, String> ext(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> trimmed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : value.entrySet()) {
            if (trimmed.size() >= EXT_MAX_ENTRIES) {
                break;
            }
            if (!hasText(entry.getKey()) || entry.getKey().length() > EXT_MAX_LENGTH) {
                continue;
            }
            String entryValue = text(entry.getValue());
            if (entryValue.length() > EXT_MAX_LENGTH) {
                entryValue = entryValue.substring(0, EXT_MAX_LENGTH);
            }
            trimmed.put(entry.getKey(), entryValue);
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private <E extends Enum<E>> String name(Optional<E> value) {
        return value.map(Enum::name).orElse("");
    }

    private long clampMs(Long value) {
        if (value == null || value < 0) {
            return 0L;
        }
        return Math.min(value, UINT32_MAX);
    }

    private int clampToInt(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private int clamp(Integer value, int max) {
        if (value == null || value < 0) {
            return 0;
        }
        return Math.min(value, max);
    }

}
