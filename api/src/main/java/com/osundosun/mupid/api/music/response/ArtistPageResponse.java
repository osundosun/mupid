package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.ArtistSummary;

/** 목록 API 공통 모양. `page` 는 0부터 센다. */
public record ArtistPageResponse(
    List<ArtistSummary> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

}
