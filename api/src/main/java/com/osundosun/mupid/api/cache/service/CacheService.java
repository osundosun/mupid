package com.osundosun.mupid.api.cache.service;

import java.time.Duration;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;

public interface CacheService {
    
    void set(String key, CacheKind kind);

    void set(String key, CacheKind kind, Duration ttl);

    void set(String key, String value, CacheKind kind);

    void set(String key, String value, CacheKind kind, Duration ttl);

    String get(String key, CacheKind kind);

    boolean exists(String key, CacheKind kind);

}
