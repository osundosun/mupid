package com.osundosun.mupid.api.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.playlist.dto.PlaylistDetail;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.error.PlaylistErrorCode;
import com.osundosun.mupid.api.playlist.request.PlaylistRenameRequest;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 수록곡 빼기와 제목 변경 검증. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class PlaylistTrackRemoveAndRenameTest {

    @Autowired
    private PlaylistService playlistService;

    @PersistenceContext
    private EntityManager em;

    private User owner;
    private User stranger;
    private Playlist playlist;
    private Music first;
    private Music second;
    private Music third;

    @BeforeEach
    void setUp() {
        owner = persistUser();
        stranger = persistUser();

        ObjectStorageFile image = persistImage();
        Artist artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);
        Album album = Album.builder()
            .artist(artist)
            .title("Blue Static")
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        first = persistMusic(album, artist, "푸른 오후");
        second = persistMusic(album, artist, "새벽 드라이브");
        third = persistMusic(album, artist, "밤의 끝");

        playlist = Playlist.builder().user(owner).title("테스트 플레이리스트").build();
        em.persist(playlist);
        em.flush();
    }

    @Test
    @DisplayName("곡을 빼면 수록곡에서 사라지고 곡 수가 줄어든다")
    void removesTrack() {
        addTrack(first);
        addTrack(second);

        playlistService.removeTrack(owner.getUserId(), playlist.getPlaylistId(), first.getMusicId());
        em.flush();

        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());
        assertEquals(1, detail.tracks().size());
        assertEquals(second.getMusicId(), detail.tracks().get(0).track().getMusic().getMusicId());
        assertEquals(1, playlist.getMusicCount());
    }

    /**
     * 트랙 순서를 다시 매기지 않는다는 계약. 빈 번호가 생겨도 정렬 결과는 같고,
     * 다음에 담는 곡은 `MAX + 1` 을 받아 빈 번호를 재사용하지 않는다.
     */
    @Test
    @DisplayName("가운데 곡을 빼도 남은 트랙 순서는 그대로고 다음 곡은 뒤에 붙는다")
    void keepsTrackOrderAfterRemoval() {
        addTrack(first);
        addTrack(second);

        playlistService.removeTrack(owner.getUserId(), playlist.getPlaylistId(), first.getMusicId());
        em.flush();
        addTrack(third);
        em.flush();

        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());
        assertEquals(2, detail.tracks().size());
        assertEquals(2, detail.tracks().get(0).track().getTrackOrder());
        assertEquals(second.getMusicId(), detail.tracks().get(0).track().getMusic().getMusicId());
        // 빈 1번을 재사용하지 않는다 — 재사용하면 새 곡이 맨 앞으로 올라간다.
        assertEquals(3, detail.tracks().get(1).track().getTrackOrder());
        assertEquals(third.getMusicId(), detail.tracks().get(1).track().getMusic().getMusicId());
    }

    @Test
    @DisplayName("담겨 있지 않은 곡을 빼면 404 다")
    void rejectsMissingTrack() {
        addTrack(first);

        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.removeTrack(
                owner.getUserId(), playlist.getPlaylistId(), second.getMusicId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_TRACK_NOT_FOUND, e.getErrorCode());
        assertEquals(1, playlist.getMusicCount());
    }

    @Test
    @DisplayName("두 번 빼면 두 번째는 404 다")
    void rejectsDoubleRemoval() {
        addTrack(first);
        playlistService.removeTrack(owner.getUserId(), playlist.getPlaylistId(), first.getMusicId());
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.removeTrack(
                owner.getUserId(), playlist.getPlaylistId(), first.getMusicId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_TRACK_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("없는 곡 번호면 404 다")
    void rejectsMissingMusic() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.removeTrack(owner.getUserId(), playlist.getPlaylistId(), -1));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("남의 플레이리스트에서는 곡을 뺄 수 없다")
    void rejectsOtherUsersPlaylistOnRemove() {
        addTrack(first);

        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.removeTrack(
                stranger.getUserId(), playlist.getPlaylistId(), first.getMusicId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
        assertEquals(1, playlist.getMusicCount());
    }

    @Test
    @DisplayName("제목을 바꿔도 수록곡은 그대로다")
    void renamesWithoutTouchingTracks() {
        addTrack(first);

        playlistService.renamePlaylist(
            owner.getUserId(), playlist.getPlaylistId(), new PlaylistRenameRequest("  구름 위  "));
        em.flush();

        // 앞뒤 공백은 잘라낸다.
        assertEquals("구름 위", playlist.getTitle());
        assertEquals(1, playlist.getMusicCount());
        assertTrue(playlistService
            .getPlaylist(owner.getUserId(), playlist.getPlaylistId())
            .tracks()
            .size() == 1);
    }

    @Test
    @DisplayName("남의 플레이리스트는 이름을 바꿀 수 없다")
    void rejectsOtherUsersPlaylistOnRename() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.renamePlaylist(
                stranger.getUserId(), playlist.getPlaylistId(), new PlaylistRenameRequest("가로채기")));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
        assertEquals("테스트 플레이리스트", playlist.getTitle());
    }

    // ─────────────────────────────────────────────────────────────

    private void addTrack(Music music) {
        playlistService.addTrack(
            owner.getUserId(), playlist.getPlaylistId(), new PlaylistTrackAddRequest(music.getMusicId()));
    }

    private ObjectStorageFile persistImage() {
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.ALBUM)
            .originalFileName("cover.jpg")
            .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
            .fileSizeBytes(1024)
            .mime("image/jpeg")
            .build();
        em.persist(image);
        return image;
    }

    private Music persistMusic(Album album, Artist artist, String title) {
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(music);
        return music;
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
