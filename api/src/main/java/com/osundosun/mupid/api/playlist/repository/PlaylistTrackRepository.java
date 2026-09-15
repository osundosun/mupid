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
     * 이 사람이 플레이리스트에 담아 둔 곡 번호 전부. 중복은 없앤다.
     *
     * 취향 리포트의 D축(국밥/개척)이 "보관함에 넣어 둔 곡" 을 가려내는 데 쓴다.
     * 좋아요 쪽은 `MusicReactionRepository.findMusicIds` 가 같은 일을 한다.
     *
     * 지워진 플레이리스트의 수록곡은 뺀다 — 화면에서 사라진 것을 보관함으로
     * 세면 안 된다. 곡 자체의 삭제 여부는 보지 않는다. 번호만 쓰는 집합이라
     * 없는 곡이 섞여도 교집합에서 저절로 빠진다.
     */
    @Query("""
        SELECT DISTINCT t.music.musicId FROM PlaylistTrack t
        WHERE t.user.userId = :userId AND t.playlist.deletedAt IS NULL
        """)
    List<Integer> findMusicIdsByUser(@Param("userId") Integer userId);

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
