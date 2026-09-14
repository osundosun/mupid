package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.response.AlbumDetailResponse;
import com.osundosun.mupid.api.music.response.AlbumPageResponse;
import com.osundosun.mupid.api.music.service.AlbumService;
import com.osundosun.mupid.api.music.service.AlbumServiceImpl;

import lombok.RequiredArgsConstructor;

/**
 * 앨범 조회. 곡 목록과 같은 이유로 공개다.
 */
@RestController
@RequestMapping("/api/v1/album")
@RequiredArgsConstructor
public class AlbumQueryController {

    private final AlbumService albumService;

    /**
     * 발매일 내림차순.
     *
     * @param q        앨범명·아티스트명 부분 일치
     * @param artistId 주면 그 아티스트의 앨범만. 쿼리 이름은 `artist_id` 다 —
     *                 SNAKE_CASE 설정은 JSON 본문에만 걸려서 직접 적어 준다
     */
    @GetMapping
    public ResponseEntity<BaseResponse<AlbumPageResponse>> searchAlbums(
        @RequestParam(required = false) String q,
        @RequestParam(name = "artist_id", required = false) Integer artistId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + AlbumServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            albumService.searchAlbums(q, artistId, page, size)));
    }

    /** 수록곡이 함께 온다. */
    @GetMapping("/{albumId}")
    public ResponseEntity<BaseResponse<AlbumDetailResponse>> getAlbum(
        @PathVariable int albumId
    ) {
        return ResponseEntity.ok(BaseResponse.of(albumService.getAlbumDetail(albumId)));
    }

}
