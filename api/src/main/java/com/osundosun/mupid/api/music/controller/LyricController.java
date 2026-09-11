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
import com.osundosun.mupid.api.music.request.LyricAddRequest;
import com.osundosun.mupid.api.music.request.LyricUpdateRequest;
import com.osundosun.mupid.api.music.response.LyricAddResponse;
import com.osundosun.mupid.api.music.service.LyricService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@SystemRole
@RestController
@RequestMapping("/api/v1/lyric")
@RequiredArgsConstructor
public class LyricController {

    private final LyricService lyricService;

    @PostMapping
    public ResponseEntity<BaseResponse<LyricAddResponse>> addLyric(
        @Valid @RequestBody LyricAddRequest request
    ) {
        int lyricId = lyricService.addLyric(request);
        return ResponseEntity.ok(BaseResponse.of(
            "가사가 추가되었습니다.",
            new LyricAddResponse(lyricId)
        ));
    }

    @PatchMapping("/{lyricId}")
    public ResponseEntity<BaseResponse<Void>> updateLyric(
        @PathVariable int lyricId,
        @Valid @RequestBody LyricUpdateRequest request
    ) {
        lyricService.updateLyric(lyricId, request);
        return ResponseEntity.ok(BaseResponse.message("가사 정보가 수정되었습니다."));
    }

    @DeleteMapping("/{lyricId}")
    public ResponseEntity<BaseResponse<Void>> deleteLyric(
        @PathVariable int lyricId
    ) {
        lyricService.deleteLyric(lyricId);
        return ResponseEntity.ok(BaseResponse.message("가사가 삭제되었습니다."));
    }
    
}
