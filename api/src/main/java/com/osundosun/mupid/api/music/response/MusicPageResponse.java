package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.MusicSummary;

/**
 * 목록 응답 공통 형태.
 *
 * 이 프로젝트의 첫 페이지네이션 API 다. 이후 목록 API 도 같은 모양을 쓴다.
 * `page` 는 0부터 센다 (Spring Data 관례).
 */
public record MusicPageResponse(

    List<MusicSummary> items,

    int page,

    int size,

    long totalElements,

    int totalPages

) {
    
}
