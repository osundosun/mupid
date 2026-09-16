package com.osundosun.mupid.api.queue.repository;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 재생 큐 문서의 Redis 입출력. decision/0022.
 *
 * `CacheService` 를 쓰지 않는다. 저기는 "넣고 빼는" 범용 KV 도우미인데 큐는
 * **읽은 판본과 같을 때만 쓴다**(CAS)는 조건이 붙는다. 그 조건을 범용 인터페이스에
 * 넣으면 큐 말고는 아무도 쓰지 않는 메서드가 생긴다. 키 이름 규칙(`CacheKind`)만
 * 공유한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class PlayQueueRepository {

    /**
     * 큐는 이 Redis 의 첫 무기한 키다. 나머지 키는 전부 TTL 이 붙어 있고
     * `maxmemory-policy` 가 설정돼 있지 않아, 가득 차면 키가 밀려나는 것이 아니라
     * 컨테이너가 죽는다. 이 TTL 이 사실상 유일한 방어선이다.
     *
     * 읽을 때마다 연장하므로 쓰는 사람의 큐는 사라지지 않는다.
     */
    private static final Duration TTL = Duration.ofDays(90);

    /**
     * 애플리케이션 ObjectMapper 를 주입받지 않는다. Spring Boot 4 의 자동설정은
     * Jackson 3 만 빈으로 등록해서 `com.fasterxml` 의 ObjectMapper 빈이 없다
     * (`EventServiceImpl` · `auth/exception` 의 핸들러들도 같은 이유로 직접 만든다).
     *
     * 게다가 이 JSON 은 **Redis 안에서만 쓰는 저장 형식**이라 응답 규약
     * (`spring.jackson.property-naming-strategy`)에 묶이면 안 된다. 그 설정을
     * 누가 바꾸면 이미 저장된 큐를 못 읽게 된다.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 읽은 판본과 같을 때만 덮어쓴다.
     *
     * GET → 애플리케이션에서 비교 → SET 으로 나누면 그 사이에 다른 기기가 쓴 것을
     * 지운다. 한 기기가 순서를 바꾸는 동안 다른 기기가 곡을 담으면 실제로 겹친다.
     *
     * 반환값 1 = 썼음, 0 = 판본이 달라 아무것도 안 함.
     */
    private static final RedisScript<Long> COMPARE_AND_SET = new DefaultRedisScript<>("""
        local raw = redis.call('GET', KEYS[1])
        local revision = 0
        if raw then
          local ok, decoded = pcall(cjson.decode, raw)
          -- 깨진 문서는 판본 0 으로 본다. 읽기 쪽도 같은 규칙이라 빈 큐로 보이고,
          -- 다음 쓰기가 정상 문서로 덮는다.
          if ok and decoded['r'] then revision = tonumber(decoded['r']) end
        end
        if revision ~= tonumber(ARGV[2]) then return 0 end
        redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
        return 1
        """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 큐를 읽는다. 없거나 깨졌으면 빈 큐다 — 큐를 못 읽었다고 음악을 못 듣게 할
     * 이유가 없다.
     */
    public PlayQueueDocument load(int userId) {
        String key = key(userId);
        String raw = stringRedisTemplate.opsForValue().get(key);
        if (raw == null) {
            return PlayQueueDocument.empty();
        }

        // 쓰는 사람의 큐는 계속 살아 있게 한다. 읽기는 자주 일어나므로 만료가
        // 가까워질 일이 없다.
        stringRedisTemplate.expire(key, TTL);

        try {
            PlayQueueDocument document = MAPPER.readValue(raw, PlayQueueDocument.class);
            return document.musicIds() == null
                ? new PlayQueueDocument(document.revision(), document.currentMusicId(), List.of())
                : document;
        } catch (JsonProcessingException e) {
            log.warn("재생 큐 문서를 읽지 못했다 user_id={}", userId, e);
            return PlayQueueDocument.empty();
        }
    }

    /**
     * `expectedRevision` 이 지금 저장된 판본과 같을 때만 쓴다.
     *
     * @return 썼으면 true. false 면 그 사이 누가 고쳤다는 뜻이고 호출부가 다시 읽는다.
     */
    public boolean compareAndSet(int userId, PlayQueueDocument document, int expectedRevision) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            // 곡 번호 목록이라 직렬화가 실패할 이유가 없다. 나면 버그다.
            log.error("재생 큐 문서를 쓰지 못했다 user_id={}", userId, e);
            return false;
        }

        Long written = stringRedisTemplate.execute(
            COMPARE_AND_SET,
            List.of(key(userId)),
            payload,
            Integer.toString(expectedRevision),
            Long.toString(TTL.toSeconds())
        );
        return written != null && written == 1L;
    }

    public void delete(int userId) {
        stringRedisTemplate.delete(key(userId));
    }

    private String key(int userId) {
        return "%s:%d".formatted(CacheKind.PlayQueue.getRedisKey(), userId);
    }

}
