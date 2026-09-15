package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.LyricBulkRequest;
import com.osundosun.mupid.api.music.response.LyricBulkResponse;
import com.osundosun.mupid.api.music.response.LyricResponse;
import com.osundosun.mupid.api.music.service.LyricService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 단위로 가사를 다루는 API.
 *
 * 곡 하나에 매달린 것이라 `/api/v1/music/{musicId}/lyric` 이다. 줄 하나를
 * 가사 번호로 다루는 쪽(`POST`·`PATCH`·`DELETE`)은 `/api/v1/lyric` 아래에
 * 따로 있다 → `LyricController`.
 *
 * **`@SystemRole` 을 클래스가 아니라 메서드에 붙인 것은 일부러다.** 조회는
 * 로그인한 누구나 하고, 교체는 배치(`ROLE_SYSTEM`)만 한다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/lyric")
@RequiredArgsConstructor
public class LyricByMusicController {

    private final LyricService lyricService;

    /** 시간 오름차순. 가사가 없으면 빈 목록이고 404 가 아니다. */
    @GetMapping
    public ResponseEntity<BaseResponse<LyricResponse>> getLyrics(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(lyricService.getLyrics(musicId)));
    }

    /**
     * 한 곡의 가사를 통째로 갈아 끼운다. **기존 가사는 지워진다.**
     *
     * `PUT` 인 이유: 같은 본문을 두 번 보내면 결과가 같다. 배치가 하는 일이
     * 정확히 "이 곡의 가사는 이 줄들이다" 이고, 줄마다 `POST` 를 부르면
     * 한 곡이 수십 개의 트랜잭션으로 흩어져 중간에 끊겼을 때 반쪽짜리 가사가
     * 남는다.
     */
    @SystemRole
    @PutMapping
    public ResponseEntity<BaseResponse<LyricBulkResponse>> replaceLyrics(
        @PathVariable int musicId,
        @Valid @RequestBody LyricBulkRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "가사가 등록되었습니다.",
            lyricService.replaceLyrics(musicId, request)
        ));
    }

}
