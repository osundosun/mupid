package com.osundosun.mupid.api.playlist.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.playlist.dto.PlaylistResponseItem;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.playlist.response.PlaylistCreateResponse;
import com.osundosun.mupid.api.playlist.response.PlaylistGetResponse;
import com.osundosun.mupid.api.playlist.response.PlaylistTrackAddResponse;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/playlist")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    /**
     * 내 플레이리스트 목록. 최근에 만든 것부터 나온다.
     *
     * 수록곡은 함께 내려보내지 않는다 — 목록 화면은 제목과 곡 수만 쓰고,
     * 곡까지 붙이면 플레이리스트 수만큼 조회가 늘어난다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<PlaylistGetResponse>> getPlaylists(
        Authentication auth
    ) {
        User user = (User)auth.getPrincipal();
        List<Playlist> playlists = playlistService.getPlaylistAll(user.getUserId());

        return ResponseEntity.ok(BaseResponse.of(new PlaylistGetResponse(playlists.stream()
            .map(it -> new PlaylistResponseItem(
                it.getPlaylistId(), /* 플레이리스트 번호 */
                it.getTitle(), /* 제목 */
                it.getMusicCount(), /* 수록곡 수 */
                it.getCreatedAt(), /* 생성 일시 */
                it.getUpdatedAt())) /* 수정 일시 */
            .toList())));
    }

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

    /**
     * 플레이리스트에 곡을 한 곡 추가한다.
     *
     * 이미 담겨 있는 곡도 200 으로 응답한다. 중복은 실패가 아니라 "이미 원하는 상태"라
     * 오류 응답으로 만들면 클라이언트가 실패로 처리하게 된다. `duplicated` 로 구분한다.
     */
    @PostMapping("/{playlistId}/track")
    public ResponseEntity<BaseResponse<PlaylistTrackAddResponse>> addTrack(
        Authentication auth,
        @PathVariable int playlistId,
        @Valid @RequestBody PlaylistTrackAddRequest request
    ) {
        User user = (User)auth.getPrincipal();
        PlaylistTrackAddResult result = playlistService.addTrack(user.getUserId(), playlistId, request);
        PlaylistTrack track = result.track();

        return ResponseEntity.ok(BaseResponse.of(
            result.duplicated()
                ? "이미 플레이리스트에 담긴 곡입니다."
                : "플레이리스트에 곡을 추가했습니다.",
            new PlaylistTrackAddResponse(
                track.getPlaylist().getPlaylistId(), /* 플레이리스트 번호 */
                track.getMusic().getMusicId(), /* 음악 번호 */
                track.getTrackOrder(), /* 트랙 순서 */
                track.getPlaylist().getMusicCount(), /* 수록곡 수 */
                result.duplicated(), /* 중복 여부 */
                track.getCreatedAt() /* 추가 일시 */
            )
        ));
    }

}
