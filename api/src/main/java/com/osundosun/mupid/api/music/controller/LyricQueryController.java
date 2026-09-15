package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.response.LyricResponse;
import com.osundosun.mupid.api.music.service.LyricService;

import lombok.RequiredArgsConstructor;

/**
 * 곡 가사 조회.
 *
 * 곡 하나에 매달린 것이라 `/api/v1/music/{musicId}/lyric` 이다. 등록 계열은
 * 가사 번호로 다루므로 `/api/v1/lyric` 아래에 따로 있다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/lyric")
@RequiredArgsConstructor
public class LyricQueryController {

    private final LyricService lyricService;

    /** 시간 오름차순. 가사가 없으면 빈 목록이고 404 가 아니다. */
    @GetMapping
    public ResponseEntity<BaseResponse<LyricResponse>> getLyrics(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(lyricService.getLyrics(musicId)));
    }

}
