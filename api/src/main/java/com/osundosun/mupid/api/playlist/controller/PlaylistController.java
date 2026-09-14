package com.osundosun.mupid.api.playlist.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.playlist.dto.PlaylistDetail;
import com.osundosun.mupid.api.playlist.dto.PlaylistResponseItem;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackResponseItem;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistRenameRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.playlist.response.PlaylistCreateResponse;
import com.osundosun.mupid.api.playlist.response.PlaylistDetailResponse;
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

    /**
     * 플레이리스트 하나와 수록곡 전부. 트랙 순서대로 나온다.
     */
    @GetMapping("/{playlistId}")
    public ResponseEntity<BaseResponse<PlaylistDetailResponse>> getPlaylist(
        Authentication auth,
        @PathVariable int playlistId
    ) {
        User user = (User)auth.getPrincipal();
        PlaylistDetail detail = playlistService.getPlaylist(user.getUserId(), playlistId);
        Playlist playlist = detail.playlist();

        return ResponseEntity.ok(BaseResponse.of(new PlaylistDetailResponse(
            playlist.getPlaylistId(), /* 플레이리스트 번호 */
            playlist.getTitle(), /* 제목 */
            playlist.getMusicCount(), /* 수록곡 수 */
            playlist.getCreatedAt(), /* 생성 일시 */
            playlist.getUpdatedAt(), /* 수정 일시 */
            detail.tracks().stream()
                .map(it -> new PlaylistTrackResponseItem(
                    it.track().getTrackOrder(), /* 트랙 순서 */
                    it.track().getMusic().getMusicId(), /* 음악 번호 */
                    it.track().getMusic().getTitle(), /* 곡명 */
                    it.track().getMusic().getArtist().getName(), /* 아티스트명 */
                    it.track().getMusic().getAlbum().getTitle(), /* 앨범명 */
                    it.durationMs(), /* 재생 길이 */
                    it.track().getSelector(), /* 선곡 주체 */
                    it.track().getCreatedAt())) /* 추가 일시 */
                .toList()
        )));
    }

    /**
     * 플레이리스트를 지운다. 소프트 삭제라 되살릴 수 있다.
     */
    @DeleteMapping("/{playlistId}")
    public ResponseEntity<BaseResponse<Void>> deletePlaylist(
        Authentication auth,
        @PathVariable int playlistId
    ) {
        User user = (User)auth.getPrincipal();
        playlistService.deletePlaylist(user.getUserId(), playlistId);
        return ResponseEntity.ok(BaseResponse.message("플레이리스트가 삭제되었습니다."));
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

    /**
     * 플레이리스트에서 곡 한 곡을 뺀다.
     *
     * 곡 추가와 달리 **담겨 있지 않으면 404** 다. 추가에서 중복을 성공으로 본 것은
     * "이미 원하는 상태" 라서인데, 빼기에서 없는 곡을 성공으로 처리하면 화면이
     * 서버와 어긋난 것을 끝까지 모른다.
     */
    @DeleteMapping("/{playlistId}/track/{musicId}")
    public ResponseEntity<BaseResponse<Void>> removeTrack(
        Authentication auth,
        @PathVariable int playlistId,
        @PathVariable int musicId
    ) {
        User user = (User)auth.getPrincipal();
        playlistService.removeTrack(user.getUserId(), playlistId, musicId);
        return ResponseEntity.ok(BaseResponse.message("플레이리스트에서 곡을 뺐습니다."));
    }

    /**
     * 플레이리스트 제목을 바꾼다. 수록곡은 그대로다.
     */
    @PatchMapping("/{playlistId}")
    public ResponseEntity<BaseResponse<PlaylistCreateResponse>> renamePlaylist(
        Authentication auth,
        @PathVariable int playlistId,
        @Valid @RequestBody PlaylistRenameRequest request
    ) {
        User user = (User)auth.getPrincipal();
        Playlist playlist = playlistService.renamePlaylist(user.getUserId(), playlistId, request);
        return ResponseEntity.ok(BaseResponse.of(
            "플레이리스트 이름을 바꿨습니다.",
            new PlaylistCreateResponse(
                playlist.getPlaylistId(), /* 플레이리스트 번호 */
                playlist.getTitle(), /* 제목 */
                playlist.getMusicCount(), /* 수록곡 수 */
                playlist.getCreatedAt() /* 생성 일시 */
            )
        ));
    }

}
