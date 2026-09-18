package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.MusicSectionSummary;

/**
 * 곡 분석 결과.
 *
 * 분석 파이프라인이 아직 없어서 대부분의 곡은 `analyzed: false`, 빈 구간으로
 * 온다. **404 가 아니다** — 곡은 있는데 분석만 없는 것은 오류가 아니다.
 * 화면은 `analyzed` 를 보고 구간 UI 를 띄울지 정한다.
 */
public record MusicAnalysisResponse(
    Integer musicId,
    /** 분석 결과가 있는가. 없으면 아래 값은 전부 비어 있다. */
    boolean analyzed,
    Boolean hasVocal,
    /** M · F · MIX. 보컬이 없거나 판정하지 못하면 null. */
    String vocalGender,
    List<MusicSectionSummary> sections
) {

}
