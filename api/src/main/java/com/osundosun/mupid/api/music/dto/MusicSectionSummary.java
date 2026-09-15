package com.osundosun.mupid.api.music.dto;

import java.util.List;

/**
 * 곡의 한 구간.
 *
 * 화면의 구간 UI 와 "이 순간" 기능이 이 값을 쓴다. 시간은 ms 다 — 곡 내 위치는
 * 이 API 전체에서 ms 로 다룬다.
 */
public record MusicSectionSummary(
    Integer sectionId,
    /** 곡 안에서의 순서. 0부터. */
    Integer index,
    /** INTRO · VERSE · PRE_CHORUS · CHORUS · BRIDGE · BREAK · INST · SOLO · OUTRO */
    String label,
    Integer startMs,
    Integer endMs,
    List<String> moods,
    List<String> instruments
) {

}
