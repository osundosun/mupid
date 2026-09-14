package com.osundosun.mupid.api.playlist.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack.PlaylistTrackId;
import com.osundosun.mupid.api.playlist.error.PlaylistErrorCode;
import com.osundosun.mupid.api.playlist.repository.PlaylistRepository;
import com.osundosun.mupid.api.playlist.repository.PlaylistTrackRepository;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class PlaylistServiceImpl implements PlaylistService {

    private final PlaylistRepository repository;
    private final PlaylistTrackRepository trackRepository;
    private final UserService userService;
    private final MusicService musicService;

    @Override
    public Playlist createPlaylist(int userId, PlaylistCreateRequest dto) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Playlist newPlaylist = Playlist.builder()
            .user(user)
            .title(dto.title())
            .build(); /* 수록곡 수는 0, 삭제 일시는 null */

        return repository.save(newPlaylist);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Playlist> getPlaylistAll(int userId) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        return repository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user);
    }

    @Override
    public PlaylistTrackAddResult addTrack(int userId, int playlistId, PlaylistTrackAddRequest dto) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Playlist playlist = repository.findByPlaylistIdAndDeletedAtIsNull(playlistId)
            .orElseThrow(() -> new BusinessException(PlaylistErrorCode.PLAYLIST_NOT_FOUND));

        /* 소유자가 없는 플레이리스트(시스템/추천)에는 사용자가 곡을 넣지 못한다. */
        if (playlist.getUser() == null || !playlist.getUser().getUserId().equals(user.getUserId())) {
            throw new BusinessException(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED);
        }

        Music music = musicService.getMusic(dto.musicId())
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        /* 중복은 오류가 아니다. 이미 담겨 있으면 그대로 두고 기존 수록곡을 돌려준다. */
        PlaylistTrack duplicated = trackRepository.findByPlaylistAndMusic(playlist, music).orElse(null);
        if (duplicated != null) {
            return new PlaylistTrackAddResult(duplicated, true);
        }

        int trackOrder = trackRepository.findLastTrackOrder(playlist) + 1;
        PlaylistTrack newTrack = PlaylistTrack.builder()
            .playlistTrackId(new PlaylistTrackId(playlist.getPlaylistId(), trackOrder))
            .playlist(playlist)
            .music(music)
            .selector(Selector.USER)
            .user(user)
            .build();

        PlaylistTrack savedTrack;
        try {
            /*
             * 같은 곡을 동시에 추가하면 위 중복 조회를 둘 다 통과한다. 마지막 방어선은
             * (playlist_id, music_id) 유니크 인덱스다. 여기서 걸리면 트랜잭션이
             * 깨져 되돌릴 수 없으므로 중복 응답 대신 409 로 알린다.
             *
             * 복합키를 직접 채워 넣어 Spring Data 는 이걸 "이미 있는 엔티티" 로 보고 merge 한다.
             * merge 는 관리 상태의 **복사본**을 돌려주므로 감사 필드(createdAt)가 채워진 쪽은
             * newTrack 이 아니라 반환값이다. 반환값을 써야 한다.
             */
            savedTrack = trackRepository.saveAndFlush(newTrack);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(PlaylistErrorCode.PLAYLIST_TRACK_CONFLICT);
        }

        playlist.setMusicCount(playlist.getMusicCount() + 1);

        return new PlaylistTrackAddResult(savedTrack, false);
    }

}
