package com.osundosun.mupid.api.playlist.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.playlist.dto.PlaylistDetail;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackDetail;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack.PlaylistTrackId;
import com.osundosun.mupid.api.playlist.error.PlaylistErrorCode;
import com.osundosun.mupid.api.playlist.repository.PlaylistRepository;
import com.osundosun.mupid.api.playlist.repository.PlaylistTrackRepository;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistRenameRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.service.StreamService;
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
    private final StreamService streamService;

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
    @Transactional(readOnly = true)
    public PlaylistDetail getPlaylist(int userId, int playlistId) {
        Playlist playlist = getMyPlaylist(userId, playlistId);
        List<PlaylistTrack> tracks = trackRepository.findAllByPlaylistWithMusic(playlist);

        /*
         * 재생 길이는 music 이 아니라 streams 에 있다. 곡마다 조회하면 수록곡 수만큼
         * 쿼리가 늘어나므로 한 번에 받아 곡 번호로 묶는다. 같은 곡에 코덱별 스트림이
         * 여럿이면 길이는 어느 쪽이든 같다.
         */
        Map<Integer, Integer> durations = new HashMap<>();
        for (Stream stream : streamService.getStreamByMusicIds(
                tracks.stream().map(it -> it.getMusic().getMusicId()).toList())) {
            /*
             * Stream.musicId 는 조회 전용 컬럼이라 방금 저장한 인스턴스에서는 null 이다.
             * 연관에서 꺼내면 항상 채워져 있다 (프록시라도 id 는 초기화 없이 읽힌다).
             */
            durations.putIfAbsent(stream.getMusic().getMusicId(), stream.getDurationMs());
        }

        return new PlaylistDetail(playlist, tracks.stream()
            .map(it -> new PlaylistTrackDetail(it, durations.get(it.getMusic().getMusicId())))
            .toList());
    }

    @Override
    public void deletePlaylist(int userId, int playlistId) {
        Playlist playlist = getMyPlaylist(userId, playlistId);

        /*
         * 소프트 삭제다. 수록곡 행은 그대로 둔다 — 되살릴 때 필요하고, 지워 봐야
         * 목록·상세가 이미 삭제된 플레이리스트를 404 로 막는다.
         */
        playlist.setDeletedAt(LocalDateTime.now());
    }

    @Override
    public PlaylistTrackAddResult addTrack(int userId, int playlistId, PlaylistTrackAddRequest dto) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        Playlist playlist = getMyPlaylist(userId, playlistId);

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

    @Override
    public void removeTrack(int userId, int playlistId, int musicId) {
        Playlist playlist = getMyPlaylist(userId, playlistId);

        Music music = musicService.getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        PlaylistTrack track = trackRepository.findByPlaylistAndMusic(playlist, music)
            .orElseThrow(() -> new BusinessException(PlaylistErrorCode.PLAYLIST_TRACK_NOT_FOUND));

        trackRepository.delete(track);

        /*
         * 수록곡 수는 음수가 될 수 없다. 곡 행과 이 숫자가 어긋난 적이 있으면
         * (동시 요청·과거 데이터) 0 아래로 내려가 목록 화면이 "-1곡" 을 보여 준다.
         */
        playlist.setMusicCount(Math.max(0, playlist.getMusicCount() - 1));
    }

    @Override
    public Playlist renamePlaylist(int userId, int playlistId, PlaylistRenameRequest dto) {
        Playlist playlist = getMyPlaylist(userId, playlistId);
        playlist.setTitle(dto.title().trim());
        return playlist;
    }

    /**
     * 내 플레이리스트를 꺼낸다. 없거나 이미 지웠으면 404, 남의 것이거나 소유자가
     * 없는 시스템/추천 플레이리스트면 403 이다.
     */
    private Playlist getMyPlaylist(int userId, int playlistId) {
        User user = userService.getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Playlist playlist = repository.findByPlaylistIdAndDeletedAtIsNull(playlistId)
            .orElseThrow(() -> new BusinessException(PlaylistErrorCode.PLAYLIST_NOT_FOUND));

        if (playlist.getUser() == null || !playlist.getUser().getUserId().equals(user.getUserId())) {
            throw new BusinessException(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED);
        }

        return playlist;
    }

}
