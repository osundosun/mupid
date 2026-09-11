package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;

@SystemRole
@RestController
@RequestMapping("/api/v1/lyric")
public class LyricController {

    @PostMapping
    public ResponseEntity<BaseResponse<?>> createLyric() {}

    @PatchMapping
    public ResponseEntity<BaseResponse<?>> updateLyric() {}

    @DeleteMapping
    public ResponseEntity<BaseResponse<?>> deleteLyric() {}
    
}
