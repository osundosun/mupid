package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.music.service.MusicServiceImpl;

import lombok.RequiredArgsConstructor;

/**
 * 곡 조회.
 *
 * 등록 계열(`MusicController`)은 클래스에 `@SystemRole` 이 붙어 있다. 조회는
 * 로그인 전에도 필요해서(첫 방문자가 온보딩 전에 곡을 둘러본다) 컨트롤러를
 * 나눴다. 공개 여부는 `SecurityConfig` 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicQueryController {

    private final MusicService musicService;

    /**
     * 곡 목록·검색.
     *
     * @param q    제목·아티스트명·앨범명 부분 일치. 비우면 전체
     * @param page 0부터 센다
     * @param size 기본 20, 최대 100
     */
    @GetMapping
    public ResponseEntity<BaseResponse<MusicPageResponse>> searchMusic(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + MusicServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(musicService.searchMusic(q, page, size)));
    }

    @GetMapping("/{musicId}")
    public ResponseEntity<BaseResponse<MusicSummary>> getMusic(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(musicService.getMusicSummary(musicId)));
    }

}
