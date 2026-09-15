package com.osundosun.mupid.api.music.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 목록·상세가 공유하는 곡 표현.
 *
 * 화면(`frontend/lib/mupid-data.ts` 의 `Track`)이 필요로 하는 값을 담는다.
 * 재생 길이는 `music` 테이블에 없어 완료된 스트림에서 가져온다.
 */
public record MusicSummary(

    int musicId,

    String title,

    LocalDateTime releaseDate,

    /**
     * 재생 길이(ms). 완료된 스트림이 없으면 null 이다.
     * `music` 에 길이 컬럼이 없어 스트림에서 끌어온다.
     */
    Integer durationMs,

    /** 재생 가능한(DONE) 스트림이 있는가. 없으면 화면에서 재생 버튼을 막는다. */
    boolean playable,

    int playCount,

    int likeCount,

    /**
     * 곡 커버 조회 경로. 곡에 표지가 없으면 null 이고, 그때 화면은 앨범
     * 커버(`album.imageUrl`)로 내려간다.
     */
    String imageUrl,

    ArtistSummary artist,

    AlbumSummary album,

    /** 아직 데이터가 없어 대부분 빈 배열이다. */
    List<String> genres,

    List<String> moods

) {

    public record ArtistSummary(int artistId, String name, String imageUrl) {
    }

    public record AlbumSummary(
        int albumId,
        String title,
        String imageUrl,
        LocalDateTime releaseDate
    ) {
    }

}
