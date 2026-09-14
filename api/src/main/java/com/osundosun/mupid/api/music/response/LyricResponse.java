package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.LyricLine;

/**
 * 한 곡의 가사.
 *
 * 아직 가사가 없는 곡이 많다. 그때는 `lines` 가 빈 배열이고 404 가 아니다 —
 * 곡은 있는데 가사만 없는 것은 오류가 아니다.
 */
public record LyricResponse(
    Integer musicId,
    List<LyricLine> lines
) {

}
