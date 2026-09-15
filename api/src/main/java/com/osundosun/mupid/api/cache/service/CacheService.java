package com.osundosun.mupid.api.cache.service;

import java.time.Duration;
import java.util.List;

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

    /**
     * 여러 키를 한 번의 왕복으로 선점한다. 결과는 `keys` 와 같은 순서다.
     *
     * 건별 `setIfAbsent` 를 배치 크기만큼 부르면 왕복도 그만큼 늘어난다. 청취 이벤트
     * 수집처럼 한 요청이 수십 건을 들고 오는 경로에서는 그 왕복이 응답 시간을 지배한다.
     *
     * 같은 키가 목록에 두 번 들어 있으면 Redis 가 순서대로 실행하므로 앞의 것만
     * `true` 다 — 한 배치 안의 중복도 이걸로 걸러진다.
     */
    List<Boolean> setIfAbsent(List<String> keys, CacheKind kind, Duration ttl);

    void delete(String key, CacheKind kind);

    String get(String key, CacheKind kind);

    boolean exists(String key, CacheKind kind);

}
