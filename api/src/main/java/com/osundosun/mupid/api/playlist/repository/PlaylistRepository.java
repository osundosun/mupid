package com.osundosun.mupid.api.playlist.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.user.entity.User;

public interface PlaylistRepository extends JpaRepository<Playlist, Integer> {

    Optional<Playlist> findByPlaylistIdAndDeletedAtIsNull(int playlistId);

    List<Playlist> findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(User user);

}
