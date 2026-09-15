package com.osundosun.mupid.api.cache.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CacheServiceImpl implements CacheService {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void set(String key, CacheKind kind) {
        String rkey = buildKey(key, kind);
        stringRedisTemplate.opsForValue().set(rkey, "");
    }

    @Override
    public void set(String key, CacheKind kind, Duration ttl) {
        String rkey = buildKey(key, kind);
        stringRedisTemplate.opsForValue().set(rkey, "", ttl);
    }

    @Override
    public void set(String key, String value, CacheKind kind) {
        String rkey = buildKey(key, kind);
        stringRedisTemplate.opsForValue().set(rkey, value);
    }

    @Override
    public void set(String key, String value, CacheKind kind, Duration ttl) {
        String rkey = buildKey(key, kind);
        stringRedisTemplate.opsForValue().set(rkey, value, ttl);
    }

    @Override
    public boolean setIfAbsent(String key, CacheKind kind, Duration ttl) {
        String rkey = buildKey(key, kind);
        return Boolean.TRUE.equals(
            stringRedisTemplate.opsForValue().setIfAbsent(rkey, "", ttl)
        );
    }

    /**
     * 파이프라인으로 한 번에 선점한다. 명령은 순서대로 실행되므로 결과 순서도 입력과 같다.
     *
     * 파이프라인 결과는 `List<Object>` 로 온다. 커넥션이 값을 돌려주지 못한 자리는
     * `null` 인데, 이걸 "선점 성공" 으로 읽으면 같은 이벤트가 두 번 발행된다.
     * `TRUE` 와 똑같은 값일 때만 성공으로 본다.
     */
    @Override
    public List<Boolean> setIfAbsent(List<String> keys, CacheKind kind, Duration ttl) {
        if (keys.isEmpty()) {
            return List.of();
        }

        List<Object> results = stringRedisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                @SuppressWarnings("unchecked")
                RedisOperations<String, String> ops = (RedisOperations<String, String>)operations;
                for (String key : keys) {
                    ops.opsForValue().setIfAbsent(buildKey(key, kind), "", ttl);
                }
                return null;
            }
        });

        List<Boolean> claimed = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            claimed.add(i < results.size() && Boolean.TRUE.equals(results.get(i)));
        }
        return claimed;
    }

    @Override
    public void delete(String key, CacheKind kind) {
        String rkey = buildKey(key, kind);
        stringRedisTemplate.delete(rkey);
    }

    @Override
    public boolean exists(String key, CacheKind kind) {
        String rkey = buildKey(key, kind);
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(rkey));
    }

    @Override
    public String get(String key, CacheKind kind) {
        String rkey = buildKey(key, kind);
        return stringRedisTemplate.opsForValue().get(rkey);
    }

    private String buildKey(String key, CacheKind kind) {
        return "%s:%s".formatted(kind.getRedisKey(), key);
    }
    
}