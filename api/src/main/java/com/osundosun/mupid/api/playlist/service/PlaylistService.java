package com.osundosun.mupid.api.playlist.service;

import java.util.List;

import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;

public interface PlaylistService {

    Playlist createPlaylist(int userId, PlaylistCreateRequest dto);

    /**
     * 내가 만든 플레이리스트 목록. 최근에 만든 것부터 나오고 삭제한 것은 빠진다.
     */
    List<Playlist> getPlaylistAll(int userId);

    /**
     * 플레이리스트 맨 뒤에 곡을 한 곡 추가한다.
     * 이미 담겨 있는 곡이면 아무것도 바꾸지 않고 중복으로 표시해 돌려준다.
     */
    PlaylistTrackAddResult addTrack(int userId, int playlistId, PlaylistTrackAddRequest dto);

}
