package com.osundosun.mupid.api.playlist.repository;

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

}
