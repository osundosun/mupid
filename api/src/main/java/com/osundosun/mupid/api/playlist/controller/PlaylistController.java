package com.osundosun.mupid.api.playlist.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.response.PlaylistCreateResponse;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/playlist")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    @PostMapping
    public ResponseEntity<BaseResponse<PlaylistCreateResponse>> createPlaylist(
        Authentication auth,
        @Valid @RequestBody PlaylistCreateRequest request
    ) {
        User user = (User)auth.getPrincipal();
        Playlist playlist = playlistService.createPlaylist(user.getUserId(), request);
        return ResponseEntity.ok(BaseResponse.of(
            "플레이리스트가 생성되었습니다.",
            new PlaylistCreateResponse(
                playlist.getPlaylistId(), /* 플레이리스트 번호 */
                playlist.getTitle(), /* 제목 */
                playlist.getMusicCount(), /* 수록곡 수 */
                playlist.getCreatedAt() /* 생성 일시 */
            )
        ));
    }

}
