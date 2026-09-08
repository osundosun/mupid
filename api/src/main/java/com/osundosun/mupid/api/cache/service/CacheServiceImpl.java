package com.osundosun.mupid.api.cache.service;

import java.time.Duration;

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