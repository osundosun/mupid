package com.osundosun.mupid.api.cache.enumerate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CacheKind {

    BlockToken("block:token");

    private final String redisKey;
    
}
