package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.dto.ArtistSummary;
import com.osundosun.mupid.api.music.response.ArtistPageResponse;
import com.osundosun.mupid.api.music.service.ArtistService;
import com.osundosun.mupid.api.music.service.ArtistServiceImpl;

import lombok.RequiredArgsConstructor;

/**
 * 아티스트 조회.
 *
 * 등록 계열(`ArtistController`)은 클래스에 `@SystemRole` 이 붙어 있다. 조회는
 * 곡 목록과 같은 이유로 공개다 — 첫 방문자가 로그인 전에 둘러본다.
 */
@RestController
@RequestMapping("/api/v1/artist")
@RequiredArgsConstructor
public class ArtistQueryController {

    private final ArtistService artistService;

    /** 이름 오름차순. `q` 는 이름 부분 일치다. */
    @GetMapping
    public ResponseEntity<BaseResponse<ArtistPageResponse>> searchArtists(
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + ArtistServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(artistService.searchArtists(q, page, size)));
    }

    @GetMapping("/{artistId}")
    public ResponseEntity<BaseResponse<ArtistSummary>> getArtist(
        @PathVariable int artistId
    ) {
        return ResponseEntity.ok(BaseResponse.of(artistService.getArtistSummary(artistId)));
    }

}
