package com.osundosun.mupid.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.event.properties.EventProperties;
import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.request.EventItemRequest;
import com.osundosun.mupid.api.event.service.EventServiceImpl;
import com.osundosun.mupid.api.history.entity.PlayHistory;
import com.osundosun.mupid.api.history.service.HistoryService;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCacheImpl;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 청취 이벤트 한 건이 <b>실제 MySQL 의 `play_histories.recommendation_reason`</b> 까지
 * 닿는지 본다.
 *
 * <p>{@code PlayHistoryReasonFromEventTest} 는 수집기가 사유를 <b>넘기는지</b>까지 본다.
 * 여기서는 그 다음 칸 — 저장이 실제로 되는지 — 를 확인한다. 이 티켓의 증상이 "컬럼이
 * 비어 있다" 였으므로, 컬럼을 읽어서 판정한다.
 *
 * <p>실제 리포지토리와 실제 트랜잭션을 쓰되 <b>Redis 와 Kafka 만 가짜</b>로 둔다. 그 둘은
 * 이 경로에서 값이 아니라 흐름(선점·발행)을 담당하고, 실물을 요구하면 이 테스트가
 * 인프라 두 개에 매달린다 ({@code EventCollectBatchTest} 와 같은 판단).
 */
@SpringBootTest
@Transactional
class PlayHistoryReasonWriteTest {

    /** 화면이 되돌려주는 상관 키(`rec_id`). */
    private static final String REC_ID = "1758000000000";
    private static final String REASON = "최근 좋아한 구간과 비슷한 코러스 구간이에요.";

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

    /** 진짜 저장 경로. 사유가 컬럼까지 가는지는 이 구현이 정한다. */
    @Autowired
    private HistoryService historyService;

    @PersistenceContext
    private EntityManager em;

    private FakeCache cache;
    private RecommendationReasonCacheImpl reasonCache;
    private EventServiceImpl collector;
    private User me;
    private Music music;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cache = new FakeCache();
        reasonCache = new RecommendationReasonCacheImpl(cache);

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>)null));

        collector = new EventServiceImpl(
            new EventProperties("localhost:9092", "mupid.listen.events", 50,
                Duration.ofMinutes(10), Duration.ofHours(72), Duration.ofMinutes(5),
                Duration.ofSeconds(3)),
            cache, kafka, historyService, reasonCache);

        me = persistUser();
        music = persistMusic();
        em.flush();
    }

    /** 컬럼에 실제로 무엇이 들어갔는지 DB 에서 읽는다. 넘긴 인자가 아니라 저장된 값이다. */
    private List<PlayHistory> savedRows() {
        return em.createQuery(
                "SELECT h FROM PlayHistory h WHERE h.user.userId = :userId ORDER BY h.playHistoryId",
                PlayHistory.class)
            .setParameter("userId", me.getUserId())
            .getResultList();
    }

    /**
     * 실제 클라이언트가 보내는 그대로 — `reason` 은 비어 있고 `rec_id` 만 있다.
     * 사유는 추천 응답에만 있었고 화면은 이벤트에 싣지 않는다.
     */
    private EventItemRequest playStart(String entryPath, String recId) {
        return new EventItemRequest(
            UUID.randomUUID().toString(),
            "PLAY_START",
            Instant.now().toString(),
            music.getMusicId().longValue(),
            UUID.randomUUID().toString(),
            entryPath,
            recId,
            null, null,
            0L, null, null, null, null, null,
            "",                    // reason — 화면은 사유를 보내지 않는다
            null, null, null, null,
            Map.of());
    }

    @Test
    @DisplayName("추천 재생의 사유가 recommendation_reason 컬럼에 실제로 들어간다")
    void fillsReasonColumnForAiPlay() {
        // 추천 응답을 내보낸 자리에서 적어 둔 것과 같은 경로로 넣는다.
        reasonCache.remember(Long.parseLong(REC_ID), music.getMusicId(), REASON);

        collector.collectEvents(me.getUserId(),
            new EventCollectRequest(List.of(playStart("RECOMMEND", REC_ID))));
        em.flush();
        em.clear();

        List<PlayHistory> rows = savedRows();
        assertEquals(1, rows.size());
        assertEquals(Selector.AI, rows.getFirst().getSelector());
        // 이 티켓의 증상이 정확히 이 칸이 NULL 이던 것이다.
        assertEquals(REASON, rows.getFirst().getRecommendationReason());
    }

    @Test
    @DisplayName("사용자가 직접 고른 재생은 AI 로 남지 않고 사유도 비어 있다")
    void userPlayStaysUserAndEmpty() {
        reasonCache.remember(Long.parseLong(REC_ID), music.getMusicId(), REASON);

        collector.collectEvents(me.getUserId(),
            new EventCollectRequest(List.of(playStart("SEARCH", REC_ID))));
        em.flush();
        em.clear();

        List<PlayHistory> rows = savedRows();
        assertEquals(1, rows.size());
        assertEquals(Selector.USER, rows.getFirst().getSelector());
        assertNull(rows.getFirst().getRecommendationReason());
    }

    @Test
    @DisplayName("컬럼 길이를 넘는 사유는 잘려서 들어간다 — INSERT 가 터지지 않는다")
    void truncatesOverlongReason() {
        // `play_histories.recommendation_reason` 이 varchar(100) 이다.
        String overlong = "가".repeat(250);
        reasonCache.remember(Long.parseLong(REC_ID), music.getMusicId(), overlong);

        collector.collectEvents(me.getUserId(),
            new EventCollectRequest(List.of(playStart("RECOMMEND", REC_ID))));
        em.flush();
        em.clear();

        PlayHistory row = savedRows().getFirst();
        assertEquals(100, row.getRecommendationReason().length());
        assertTrue(overlong.startsWith(row.getRecommendationReason()));
    }

    // ─────────────────────────────────────────────────────────────

    private User persistUser() {
        User user = User.builder()
            .email("reason-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        return user;
    }

    private Music persistMusic() {
        // `artists.image_file_id`·`albums.image_file_id` 가 NOT NULL 이라 표지를 먼저 만든다.
        ObjectStorageFile image = persistImage();
        Artist artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);
        Album album = Album.builder()
            .artist(artist)
            .title("Blue Static")
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        Music row = Music.builder()
            .album(album)
            .artist(artist)
            .title("푸른 오후")
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(row);
        return row;
    }

    private ObjectStorageFile persistImage() {
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.ALBUM)
            .originalFileName("cover.jpg")
            .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
            .fileSizeBytes(1024)
            .mime("image/jpeg")
            .build();
        em.persist(image);
        return image;
    }

}
