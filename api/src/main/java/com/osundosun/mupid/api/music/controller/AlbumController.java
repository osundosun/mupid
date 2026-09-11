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
import com.osundosun.mupid.api.music.request.AlbumCreateRequest;
import com.osundosun.mupid.api.music.request.AlbumUpdateRequest;
import com.osundosun.mupid.api.music.response.AlbumCreateResponse;
import com.osundosun.mupid.api.music.service.AlbumService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@SystemRole
@RestController
@RequestMapping("/api/v1/album")
@RequiredArgsConstructor
public class AlbumController {

    private final AlbumService albumService;

    @PostMapping
    public ResponseEntity<BaseResponse<AlbumCreateResponse>> createAlbum(
        @Valid @RequestBody AlbumCreateRequest request
    ) {
        int albumId = albumService.createAlbum(request);
        return ResponseEntity.ok(BaseResponse.of(
            "앨범이 생성되었습니다.",
            new AlbumCreateResponse(albumId)
        ));
    }

    @PatchMapping("/{albumId}")
    public ResponseEntity<BaseResponse<Void>> updateAlbum(
        @PathVariable int albumId,
        @Valid @RequestBody AlbumUpdateRequest request
    ) {
        albumService.updateAlbum(albumId, request);
        return ResponseEntity.ok(BaseResponse.message("앨범 정보가 수정되었습니다."));
    }

    @DeleteMapping("/{albumId}")
    public ResponseEntity<BaseResponse<Void>> deleteAlbum(
        @PathVariable int albumId
    ) {
        albumService.deleteAlbum(albumId);
        return ResponseEntity.ok(BaseResponse.message("앨범이 삭제되었습니다."));
    }
    
}
