package com.osundosun.mupid.api.ai.dto;

import com.osundosun.mupid.api.music.enumerate.VocalGender;

/**
 * 곡 한 줄 — 곡 번호·제목·보컬 판정. 태그와 구간은 따로 읽어 붙인다.
 *
 * <p>{@code music_analysis} 는 분석이 끝난 곡에만 행이 있으므로 LEFT JOIN 이다.
 * 행이 없으면 {@code hasVocal} 이 {@code null} 이고, 그것은 "보컬이 없다" 가 아니라
 * <b>"모른다"</b> 다. 둘을 섞으면 미분석 곡이 전부 연주곡으로 추천된다.
 */
public record AiCatalogTrackRow(
    Integer musicId,
    String title,
    Boolean hasVocal,
    VocalGender vocalGender
) {
}
