package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.AlbumSummary;

/** 목록 API 공통 모양. `page` 는 0부터 센다. */
public record AlbumPageResponse(
    List<AlbumSummary> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

}
