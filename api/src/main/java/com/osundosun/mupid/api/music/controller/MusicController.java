package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;
import com.osundosun.mupid.api.music.response.MusicCreateResponse;
import com.osundosun.mupid.api.music.service.MusicService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@SystemRole
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicController {

    private final MusicService musicService;

    @PostMapping
    public ResponseEntity<BaseResponse<MusicCreateResponse>> addMusic(
        @Valid @RequestBody MusicAddRequest request
    ) {
        int musicid = musicService.addMusic(request);
        return ResponseEntity.ok(BaseResponse.of(
            "음악이 추가되었습니다.",
            new MusicCreateResponse(musicid)
        ));
    }

    @PatchMapping("/{musicId}")
    public ResponseEntity<BaseResponse<Void>> updateMusic(
        @PathVariable int musicId,
        @Valid @RequestBody MusicUpdateRequest request
    ) {
        musicService.updateMusic(musicId, request);
        return ResponseEntity.ok(BaseResponse.message("음악 정보가 수정되었습니다."));
    }

    @DeleteMapping("/{musicId}")
    public ResponseEntity<BaseResponse<Void>> deleteMusic(
        @PathVariable int musicId
    ) {
        musicService.deleteMusic(musicId);
        return ResponseEntity.ok(BaseResponse.message("음악이 삭제되었습니다."));
    }

}
