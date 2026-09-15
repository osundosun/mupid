package com.osundosun.mupid.api.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.request.PlaylistCreateRequest;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 플레이리스트 목록 조회 검증. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class PlaylistGetAllTest {

    @Autowired
    private PlaylistService playlistService;

    @PersistenceContext
    private EntityManager em;

    private User owner;
    private User stranger;

    @BeforeEach
    void setUp() {
        owner = persistUser();
        stranger = persistUser();
        em.flush();
    }

    @Test
    @DisplayName("플레이리스트가 없으면 빈 목록이다")
    void returnsEmptyList() {
        assertTrue(playlistService.getPlaylistAll(owner.getUserId()).isEmpty());
    }

    @Test
    @DisplayName("내가 만든 플레이리스트만 최근 생성 순으로 나온다")
    void returnsMyPlaylistsNewestFirst() {
        Playlist older = persistPlaylist(owner, "먼저 만든 것", LocalDateTime.now().minusDays(1));
        Playlist newer = playlistService.createPlaylist(
            owner.getUserId(), new PlaylistCreateRequest("나중에 만든 것"));
        persistPlaylist(stranger, "남의 것", LocalDateTime.now());
        em.flush();

        List<Playlist> playlists = playlistService.getPlaylistAll(owner.getUserId());

        assertEquals(2, playlists.size());
        assertEquals(newer.getPlaylistId(), playlists.get(0).getPlaylistId());
        assertEquals(older.getPlaylistId(), playlists.get(1).getPlaylistId());
    }

    @Test
    @DisplayName("삭제한 플레이리스트는 빠진다")
    void excludesDeleted() {
        Playlist alive = persistPlaylist(owner, "살아 있는 것", LocalDateTime.now());
        Playlist deleted = persistPlaylist(owner, "지운 것", LocalDateTime.now());
        deleted.setDeletedAt(LocalDateTime.now());
        em.flush();

        List<Playlist> playlists = playlistService.getPlaylistAll(owner.getUserId());

        assertEquals(1, playlists.size());
        assertEquals(alive.getPlaylistId(), playlists.get(0).getPlaylistId());
    }

    @Test
    @DisplayName("소유자가 없는 시스템 플레이리스트는 누구의 목록에도 없다")
    void excludesSystemPlaylist() {
        persistPlaylist(null, "추천 플레이리스트", LocalDateTime.now());
        em.flush();

        assertTrue(playlistService.getPlaylistAll(owner.getUserId()).isEmpty());
    }

    @Test
    @DisplayName("수록곡 수가 함께 나온다")
    void carriesMusicCount() {
        Playlist playlist = persistPlaylist(owner, "곡이 담긴 것", LocalDateTime.now());
        playlist.setMusicCount(3);
        em.flush();

        List<Playlist> playlists = playlistService.getPlaylistAll(owner.getUserId());

        assertEquals(3, playlists.get(0).getMusicCount());
        assertFalse(playlists.get(0).getTitle().isBlank());
    }

    @Test
    @DisplayName("없는 사용자면 404 다")
    void rejectsMissingUser() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.getPlaylistAll(-1));

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    // ─────────────────────────────────────────────────────────────

    private Playlist persistPlaylist(User user, String title, LocalDateTime createdAt) {
        Playlist playlist = Playlist.builder().user(user).title(title).build();
        em.persist(playlist);
        em.flush();
        /*
         * createdAt 은 엔티티에서 updatable = false 라 setter 로 바꿔도 DB 에 닿지 않는다.
         * 정렬은 DB 가 하므로 생성 시각은 네이티브 UPDATE 로 직접 넣는다.
         */
        em.createNativeQuery("UPDATE playlists SET created_at = :createdAt WHERE playlist_id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", playlist.getPlaylistId())
            .executeUpdate();
        return playlist;
    }

    private User persistUser() {
        User user = User.builder()
            .email("test-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        return user;
    }

}
