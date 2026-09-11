package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicAddRequest;

import jakarta.validation.Valid;

@SystemRole
@RestController
@RequestMapping("/api/v1/music")
public class MusicController {

    @PostMapping
    public ResponseEntity<BaseResponse<?>> addMusic(
        @Valid @RequestBody MusicAddRequest request
    ) {}

    @PatchMapping
    public ResponseEntity<BaseResponse<?>> updateMusic() {}

    @PostMapping("/release")
    public ResponseEntity<BaseResponse<?>> setReleaseDate() {}

    @DeleteMapping
    public ResponseEntity<BaseResponse<?>> deleteMusic() {}

}
