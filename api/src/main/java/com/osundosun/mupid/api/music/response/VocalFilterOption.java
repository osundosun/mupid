package com.osundosun.mupid.api.music.response;

import com.osundosun.mupid.api.music.enumerate.VocalFilter;

/** 탐색 화면에 보여 줄 보컬 필터 한 개. */
public record VocalFilterOption(
    String value,
    String displayName
) {

    public static VocalFilterOption of(VocalFilter filter) {
        return new VocalFilterOption(filter.name().toLowerCase(), filter.getDisplayName());
    }
}
