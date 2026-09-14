package com.osundosun.mupid.api.playlist.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.entity.PlaylistTrack;

public interface PlaylistTrackRepository
    extends JpaRepository<PlaylistTrack, PlaylistTrack.PlaylistTrackId> {

    Optional<PlaylistTrack> findByPlaylistAndMusic(Playlist playlist, Music music);

    /**
     * 플레이리스트의 마지막 트랙 순서. 수록곡이 없으면 0 이다.
     */
    @Query("""
        SELECT COALESCE(MAX(t.playlistTrackId.trackOrder), 0)
        FROM PlaylistTrack t
        WHERE t.playlist = :playlist
        """)
    int findLastTrackOrder(@Param("playlist") Playlist playlist);

    /**
     * 수록곡을 트랙 순서대로. 곡·아티스트·앨범을 함께 읽어 N+1 을 막는다.
     * 삭제된 곡은 빠진다 — 화면에 없는 곡을 보여 줄 이유가 없다.
     */
    @Query("""
        SELECT t FROM PlaylistTrack t
        JOIN FETCH t.music m
        JOIN FETCH m.artist
        JOIN FETCH m.album
        WHERE t.playlist = :playlist AND m.isDeleted = false
        ORDER BY t.playlistTrackId.trackOrder ASC
        """)
    List<PlaylistTrack> findAllByPlaylistWithMusic(@Param("playlist") Playlist playlist);

}
