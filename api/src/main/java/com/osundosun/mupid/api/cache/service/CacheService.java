package com.osundosun.mupid.api.cache.service;

import java.time.Duration;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;

public interface CacheService {
    
    void set(String key, CacheKind kind);

    void set(String key, CacheKind kind, Duration ttl);

    void set(String key, String value, CacheKind kind);

    void set(String key, String value, CacheKind kind, Duration ttl);

    /**
     * 키가 없을 때만 넣고, 넣었는지를 돌려준다 (SETNX).
     *
     * exists + set 으로 흉내내면 두 요청이 같은 키를 동시에 통과할 수 있어
     * 멱등 처리에는 쓸 수 없다.
     */
    boolean setIfAbsent(String key, CacheKind kind, Duration ttl);

    void delete(String key, CacheKind kind);

    String get(String key, CacheKind kind);

    boolean exists(String key, CacheKind kind);

}
