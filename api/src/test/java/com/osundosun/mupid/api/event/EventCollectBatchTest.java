package com.osundosun.mupid.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.event.properties.EventProperties;
import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.request.EventItemRequest;
import com.osundosun.mupid.api.event.response.EventCollectResponse;
import com.osundosun.mupid.api.event.service.EventServiceImpl;
import com.osundosun.mupid.api.history.service.HistoryService;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;

import tools.jackson.databind.json.JsonMapper;

/**
 * 수집기의 배치 처리 검증.
 *
 * `@SpringBootTest` 를 쓰지 않는다 — 이 경로에 필요한 것은 Redis 선점과 Kafka 발행의
 * **순서와 개수**뿐이고, 그건 가짜로 충분하다. MySQL 이 떠 있어야 도는 테스트를 하나
 * 더 늘릴 이유가 없다 (convention/testing.md).
 */
class EventCollectBatchTest {

    /**
     * 파이프라인 선점을 흉내 내는 가짜. 실제 Redis 와 같은 의미를 지킨다 —
     * 명령은 순서대로 실행되고, 이미 잡힌 키는 `false` 다.
     */
    private static final class FakeCache implements CacheService {

        private final Set<String> taken = new HashSet<>();
        /** 한 요청이 Redis 를 몇 번 왕복했는가. */
        private int roundTrips = 0;

        @Override
        public boolean setIfAbsent(String key, CacheKind kind, Duration ttl) {
            roundTrips++;
            return taken.add(key);
        }

        @Override
        public List<Boolean> setIfAbsent(List<String> keys, CacheKind kind, Duration ttl) {
            roundTrips++;
            List<Boolean> claimed = new ArrayList<>(keys.size());
            for (String key : keys) {
                claimed.add(taken.add(key));
            }
            return claimed;
        }

        @Override
        public void delete(String key, CacheKind kind) {
            taken.remove(key);
        }

        @Override
        public void set(String key, CacheKind kind) {
        }

        @Override
        public void set(String key, CacheKind kind, Duration ttl) {
        }

        @Override
        public void set(String key, String value, CacheKind kind) {
        }

        @Override
        public void set(String key, String value, CacheKind kind, Duration ttl) {
        }

        @Override
        public String get(String key, CacheKind kind) {
            return null;
        }

        @Override
        public boolean exists(String key, CacheKind kind) {
            return taken.contains(key);
        }

    }

    private FakeCache cache;
    private List<String> published;
    private EventServiceImpl service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cache = new FakeCache();
        published = new ArrayList<>();

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            published.add(invocation.getArgument(2));
            return CompletableFuture.completedFuture((SendResult<String, String>)null);
        });

        HistoryService history = mock(HistoryService.class);

        EventProperties props = new EventProperties(
            "localhost:9092",
            "mupid.listen.events",
            50,
            Duration.ofMinutes(10),
            Duration.ofHours(72),
            Duration.ofMinutes(5),
            Duration.ofSeconds(3)
        );

        service = new EventServiceImpl(props, cache, kafka, history,
            mock(RecommendationReasonCache.class));
    }

    private EventItemRequest heartbeat(String eventId, long positionMs) {
        return new EventItemRequest(
            eventId,
            "PLAY_PROGRESS",
            Instant.now().toString(),
            1001L,
            UUID.randomUUID().toString(),
            "RECOMMEND",
            null, null, null,
            positionMs,
            positionMs - 3000,
            null, null, null, null, null, null, null, null, null,
            Map.of()
        );
    }

    @Test
    @DisplayName("배치 전체의 멱등 선점을 한 번의 왕복으로 끝낸다")
    void claimsWholeBatchInOneRoundTrip() {
        List<EventItemRequest> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(heartbeat(UUID.randomUUID().toString(), 3000L * (i + 1)));
        }

        EventCollectResponse response = service.collectEvents(7, new EventCollectRequest(events));

        assertEquals(20, response.accepted());
        assertEquals(20, published.size());
        // 건별로 부르던 때는 여기가 20 이었다. 배치 크기가 늘어도 1 이어야 한다.
        assertEquals(1, cache.roundTrips);
    }

    @Test
    @DisplayName("같은 event_id 는 한 배치 안에서도, 재전송에서도 한 번만 발행된다")
    void dropsDuplicatesWithinAndAcrossBatches() {
        String repeated = UUID.randomUUID().toString();
        EventCollectResponse first = service.collectEvents(7, new EventCollectRequest(List.of(
            heartbeat(repeated, 3000),
            heartbeat(UUID.randomUUID().toString(), 6000),
            heartbeat(repeated, 3000)
        )));

        assertEquals(2, first.accepted());
        assertEquals(1, first.duplicated());
        assertEquals(2, published.size());

        // 네트워크가 끊겨 큐가 같은 배치를 다시 보냈다.
        EventCollectResponse retry = service.collectEvents(7, new EventCollectRequest(List.of(
            heartbeat(repeated, 3000)
        )));

        assertEquals(0, retry.accepted());
        assertEquals(1, retry.duplicated());
        assertEquals(2, published.size());
    }

    /**
     * 회귀 테스트. `playlist_id` 가 `Integer` 였을 때, 프론트가 보낸 클라이언트 지역
     * id(`Date.now()` ≈ 1.76e12)에 Jackson 이 `InputCoercionException` 을 던져
     * **배치 전체가 400** 이 됐다. 같이 실려 가던 재생 기록까지 사라졌고, 프론트는
     * 4xx 를 재시도하지 않으므로 그대로 유실됐다.
     */
    @Test
    @DisplayName("범위를 넘는 id 한 개가 배치 전체를 무너뜨리지 않는다")
    void outOfRangeIdDoesNotBreakTheBatch() {
        String body = """
            {"events":[
              {"eventId":"%s","eventType":"PLAY_START","occurredAt":"%s",
               "trackId":1001,"positionMs":0},
              {"eventId":"%s","eventType":"PLAYLIST_ADD","occurredAt":"%s",
               "trackId":1001,"playlistId":1758000000000,"positionMs":42000}
            ]}
            """.formatted(
            UUID.randomUUID(), Instant.now(),
            UUID.randomUUID(), Instant.now()
        );

        // 1) 파싱 단계에서 터지지 않는다. 여기가 실제로 400 을 만들던 자리다.
        EventCollectRequest request = JsonMapper.builder().build()
            .readValue(body, EventCollectRequest.class);
        assertEquals(1758000000000L, request.events().get(1).playlistId());

        // 2) 두 건 모두 발행된다. 범위를 넘은 것은 그 필드만 0("모름")이 된다.
        EventCollectResponse response = service.collectEvents(7, request);
        assertEquals(2, response.accepted());
        assertEquals(0, response.rejected());
        assertTrue(published.get(1).contains("\"playlist_id\":0"));
        // 곡과 재생 위치는 살아 있어야 한다 — 구간 신호(+0.8)는 이 둘로 만들어진다.
        assertTrue(published.get(1).contains("\"track_id\":1001"));
        assertTrue(published.get(1).contains("\"position_ms\":42000"));
    }

    @Test
    @DisplayName("규약에 어긋난 한 건은 그 건만 버리고 나머지는 통과시킨다")
    void rejectsOnlyTheOffendingEvent() {
        Map<String, String> ext = new HashMap<>();
        ext.put("source", "test");

        EventCollectResponse response = service.collectEvents(7, new EventCollectRequest(List.of(
            heartbeat(UUID.randomUUID().toString(), 3000),
            // event_id 가 UUID 가 아니다. 멱등 키이자 ClickHouse 의 중복 판정 키라 못 쓴다.
            heartbeat("not-a-uuid", 6000),
            new EventItemRequest(
                UUID.randomUUID().toString(),
                // 목록에 없는 이벤트 종류.
                "PLAY_TELEPORT",
                Instant.now().toString(),
                1001L, null, null, null, null, null,
                9000L, null, null, null, null, null, null, null, null, null, null,
                ext
            ),
            heartbeat(UUID.randomUUID().toString(), 9000)
        )));

        assertEquals(2, response.accepted());
        assertEquals(2, response.rejected());
        assertEquals(0, response.duplicated());
        assertEquals(2, published.size());
        // 버려진 건은 멱등 키도 잡지 않는다. 클라이언트가 고쳐 보낼 수 있어야 한다.
        assertTrue(cache.taken.size() == 2);
    }

}
