package com.osundosun.mupid.api.playlist.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.user.entity.User;

public interface PlaylistRepository extends JpaRepository<Playlist, Integer> {

    Optional<Playlist> findByPlaylistIdAndDeletedAtIsNull(int playlistId);

    /**
     * <b>삭제된 플레이리스트도</b> 찾는다. 살아 있는지 아닌지는 부르는 쪽이 판단한다.
     *
     * <p>"없다"(404)와 "지워졌다"(410)를 나누려면 행 자체가 있는지 먼저 봐야 한다.
     * 위 `…DeletedAtIsNull` 은 삭제된 것을 걸러서 둘 다 {@code empty} 다.
     *
     * <p>권한 검사에 쓰지 않는다 — 삭제된 목록의 주인을 확인하려면
     * {@code getMyPlaylist} 가 아니라 호출부가 소유자 비교를 직접 해야 한다
     * (`PLAYLIST_ACCESS_DENIED` 403 과 `PLAYLIST_GONE` 410 을 섞지 않기 위해서다).
     */
    Optional<Playlist> findByPlaylistId(int playlistId);

    List<Playlist> findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User user);

}
