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
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.ArtistCreateRequest;
import com.osundosun.mupid.api.music.request.ArtistUpdateRequest;
import com.osundosun.mupid.api.music.response.ArtistCreateResponse;
import com.osundosun.mupid.api.music.service.ArtistService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@SystemRole
@RestController
@RequestMapping("/api/v1/artist")
@RequiredArgsConstructor
public class ArtistController {

    private final ArtistService artistService;

    @PostMapping
    public ResponseEntity<BaseResponse<ArtistCreateResponse>> createArtist(
        @Valid @RequestBody ArtistCreateRequest request
    ) {
        int artistId = artistService.createArtist(request.name());
        return ResponseEntity.ok(BaseResponse.of(
            "아티스트가 생성되었습니다.",
            new ArtistCreateResponse(artistId)
        ));
    }

    @PatchMapping("/{artistId}")
    public ResponseEntity<BaseResponse<Void>> updateArtist(
        @PathVariable int artistId,
        @Valid @RequestBody ArtistUpdateRequest request
    ) {
        if (request.name() != null) {
            if (!artistService.renameArtist(artistId, request.name())) {
                throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
            }
        }
        return ResponseEntity.ok(BaseResponse.message("아티스트 정보가 수정되었습니다."));
    }

    @DeleteMapping("/{artistId}")
    public ResponseEntity<BaseResponse<Void>> deleteArtist(
        @PathVariable int artistId
    ) {
        artistService.deleteArtist(artistId);
        return ResponseEntity.ok(BaseResponse.message("아티스트가 삭제되었습니다."));
    }
    
}
