package com.osundosun.mupid.api.music.dto;

/** 가사 한 줄. `timeMs` 는 그 줄이 나오는 곡 내 시점이다. */
public record LyricLine(
    Integer lyricId,
    Integer timeMs,
    String text
) {

}
