package com.osundosun.mupid.api.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.event.properties.EventProperties;
import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.request.EventItemRequest;
import com.osundosun.mupid.api.event.service.EventServiceImpl;
import com.osundosun.mupid.api.history.entity.PlayHistory;
import com.osundosun.mupid.api.history.service.HistoryService;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCacheImpl;

/**
 * 추천으로 들어온 재생에 `recommendation_reason` 이 남는지 검증한다.
 *
 * <p>이 값이 비는 이유는 저장 로직이 아니라 <b>사유가 이벤트에 실려 오지 않기 때문</b>이다.
 * 재생 하나에는 진입 경로(`entry_path`)만 실리고, 사유는 진입 전에 본 <b>추천 응답</b>에만
 * 있다 (`knowledge/domain/listening-events.md` — `PLAY_START` 의 고유 필드는
 * `position_ms` 와 `rec_id`·`rec_type`·`rec_rank` 뿐이다).
 *
 * <p>그래서 사유를 보내는 대신, 화면이 이미 되돌려주는 상관 키 `rec_id` 로 그 재생 직전의
 * 추천을 되짚는다. 이 테스트는 재생 이벤트에 `rec_id` 만 실린 상태 — 실제 클라이언트가
 * 보내는 그대로 — 에서 사유가 기록에 닿는지를 본다.
 *
 * <p>`@SpringBootTest` 를 쓰지 않는다. 확인할 것은 "무엇이 기록으로 넘어갔는가" 뿐이고,
 * 그건 대역으로 충분하다 ({@code EventCollectBatchTest} · convention/testing.md 와 같은 판단).
 */
class PlayHistoryReasonFromEventTest {

    private static final int USER_ID = 7;
    private static final int MUSIC_ID = 1797;
    private static final String REASON = "최근 좋아한 구간과 비슷한 코러스 구간이에요.";

    /**
     * 선점과 값 보관을 흉내 내는 가짜. 실제 Redis 처럼 **키별로 값을 하나** 들고 있다.
     */
    private static final class FakeCache implements CacheService {

        private final Set<String> taken = new HashSet<>();
        private final Map<String, String> values = new HashMap<>();

        @Override
        public boolean setIfAbsent(String key, CacheKind kind, Duration ttl) {
            return taken.add(key);
        }

        @Override
        public List<Boolean> setIfAbsent(List<String> keys, CacheKind kind, Duration ttl) {
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
            values.put("%s:%s".formatted(kind.getRedisKey(), key), value);
        }

        @Override
        public void set(String key, String value, CacheKind kind, Duration ttl) {
            set(key, value, kind);
        }

        @Override
        public String get(String key, CacheKind kind) {
            return values.get("%s:%s".formatted(kind.getRedisKey(), key));
        }

        @Override
        public boolean exists(String key, CacheKind kind) {
            return taken.contains(key);
        }

    }

    private FakeCache cache;
    private List<String> published;
    /** 기록으로 **넘어간 인자**. MySQL 이 없어도 무엇이 저장됐는지 볼 수 있다. */
    private List<PlayHistory> saved;
    private RecommendationReasonCacheImpl reasonCache;
    private EventServiceImpl service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cache = new FakeCache();
        published = new ArrayList<>();
        saved = new ArrayList<>();

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            published.add(invocation.getArgument(2));
            return CompletableFuture.completedFuture((SendResult<String, String>)null);
        });

        HistoryService history = mock(HistoryService.class);
        when(history.recordPlay(anyInt(), anyInt(), any(), any())).thenAnswer(invocation -> {
            saved.add(PlayHistory.builder()
                .selector(invocation.getArgument(2))
                .recommendationReason(invocation.getArgument(3))
                .build());
            return true;
        });

        EventProperties props = new EventProperties(
            "localhost:9092",
            "mupid.listen.events",
            50,
            Duration.ofMinutes(10),
            Duration.ofHours(72),
            Duration.ofMinutes(5),
            Duration.ofSeconds(3)
        );

        reasonCache = new RecommendationReasonCacheImpl(cache);
        service = new EventServiceImpl(props, cache, kafka, history, reasonCache);
    }

    /**
     * 실제 클라이언트가 보내는 재생 시작 한 건.
     *
     * <p>`reason` 을 **비워 둔다.** 프론트 SDK 는 재생 이벤트에 사유를 싣지 않는다 —
     * `PLAY_ERROR` 의 `reason` 만 채운다. 이 빈 칸이 곧 이 티켓의 원인이다.
     */
    private EventItemRequest playStart(String eventId, String entryPath, String recId) {
        return new EventItemRequest(
            eventId,
            "PLAY_START",
            Instant.now().toString(),
            (long)MUSIC_ID,
            UUID.randomUUID().toString(),
            entryPath,
            recId,
            null, null,
            0L, null, null, null, null, null,
            "",                    // reason — 화면은 사유를 보내지 않는다
            null, null, null, null,
            Map.of()
        );
    }

    @Test
    @DisplayName("추천이 준 사유가 재생 기록에 남는다")
    void storesReasonOfTheRecommendation() {
        // 화면이 재생 직전에 추천을 받아 두었다. 사유는 응답에만 있고 이벤트에는 없다.
        // 기록은 추천을 내보낸 자리에서 하는 것과 **같은 경로**로 넣는다 — 키가 어긋나면
        // 여기서 드러난다.
        reasonCache.remember(1758000000000L, MUSIC_ID, REASON);

        service.collectEvents(USER_ID, new EventCollectRequest(List.of(
            playStart(UUID.randomUUID().toString(), "RECOMMEND", "1758000000000"))));

        assertEquals(1, saved.size());
        assertEquals(Selector.AI, saved.getFirst().getSelector());
        assertEquals(REASON, saved.getFirst().getRecommendationReason());
    }

    @Test
    @DisplayName("사유를 모르는 추천 재생도 기록은 남긴다 — 진입 경로만으로도 선곡 주체는 안다")
    void recordsPlayWithoutReasonWhenLookupMisses() {
        service.collectEvents(USER_ID, new EventCollectRequest(List.of(
            playStart(UUID.randomUUID().toString(), "RECOMMEND", "알 수 없는 추천 번호"))));

        assertEquals(1, saved.size());
        assertEquals(Selector.AI, saved.getFirst().getSelector());
        assertNull(saved.getFirst().getRecommendationReason());
    }

    @Test
    @DisplayName("사용자가 직접 고른 재생에는 사유가 붙지 않는다")
    void userPickedPlayHasNoReason() {
        // 추천 경로가 아니면 사유를 찾지 않는다. 적어 둔 값이 있어도 붙지 않는다.
        reasonCache.remember(1758000000000L, MUSIC_ID, REASON);

        service.collectEvents(USER_ID, new EventCollectRequest(List.of(
            playStart(UUID.randomUUID().toString(), "SEARCH", null))));

        assertEquals(1, saved.size());
        assertEquals(Selector.USER, saved.getFirst().getSelector());
        assertNull(saved.getFirst().getRecommendationReason());
    }

    @Test
    @DisplayName("사유 조회가 터져도 이벤트 수집은 계속된다 — 부가 정보다")
    void lookupFailureDoesNotBlockCollection() {
        CacheService broken = mock(CacheService.class);
        when(broken.setIfAbsent(any(List.class), any(), any())).thenReturn(List.of(true));
        when(broken.get(anyString(), any())).thenThrow(new IllegalStateException("Redis down"));

        HistoryService history = mock(HistoryService.class);
        when(history.recordPlay(anyInt(), anyInt(), any(), any())).thenAnswer(invocation -> {
            saved.add(PlayHistory.builder()
                .selector(invocation.getArgument(2))
                .recommendationReason(invocation.getArgument(3))
                .build());
            return true;
        });

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>)null));

        EventServiceImpl tolerant = new EventServiceImpl(
            new EventProperties("localhost:9092", "mupid.listen.events", 50,
                Duration.ofMinutes(10), Duration.ofHours(72), Duration.ofMinutes(5),
                Duration.ofSeconds(3)),
            broken, kafka, history, new RecommendationReasonCacheImpl(broken));

        tolerant.collectEvents(USER_ID, new EventCollectRequest(List.of(
            playStart(UUID.randomUUID().toString(), "RECOMMEND", "1758000000000"))));

        assertEquals(1, saved.size());
        assertEquals(Selector.AI, saved.getFirst().getSelector());
        assertNull(saved.getFirst().getRecommendationReason());
    }

}
