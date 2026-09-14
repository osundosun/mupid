package com.osundosun.mupid.api.music.dto;

/** 목록·상세가 함께 쓰는 아티스트 표현. */
public record ArtistSummary(
    Integer artistId,
    String name,
    /** 바로 부를 수 있는 조회 경로. 파일이 없으면 null. */
    String imageUrl
) {

}
