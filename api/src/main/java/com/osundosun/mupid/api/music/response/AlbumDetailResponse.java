package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.AlbumSummary;
import com.osundosun.mupid.api.music.dto.MusicSummary;

/**
 * 앨범 상세. 수록곡이 함께 온다.
 *
 * 수록곡 표현은 `곡 목록 조회` 와 같다. 화면이 목록에서 쓰던 코드를 그대로 쓴다.
 */
public record AlbumDetailResponse(
    AlbumSummary album,
    List<MusicSummary> tracks
) {

}
