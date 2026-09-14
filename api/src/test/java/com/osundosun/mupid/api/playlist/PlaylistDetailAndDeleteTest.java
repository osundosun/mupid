package com.osundosun.mupid.api.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.playlist.dto.PlaylistDetail;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.error.PlaylistErrorCode;
import com.osundosun.mupid.api.playlist.request.PlaylistTrackAddRequest;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.Codec;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 플레이리스트 상세 조회와 삭제 검증. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class PlaylistDetailAndDeleteTest {

    @Autowired
    private PlaylistService playlistService;

    @PersistenceContext
    private EntityManager em;

    private User owner;
    private User stranger;
    private Playlist playlist;
    private Music first;
    private Music second;

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

        playlist = Playlist.builder().user(owner).title("테스트 플레이리스트").build();
        em.persist(playlist);
        em.flush();
    }

    @Test
    @DisplayName("수록곡이 트랙 순서대로 곡·아티스트·앨범과 함께 나온다")
    void returnsTracksInOrder() {
        addTrack(first);
        addTrack(second);

        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());

        assertEquals(2, detail.tracks().size());
        assertEquals(1, detail.tracks().get(0).track().getTrackOrder());
        assertEquals(first.getMusicId(), detail.tracks().get(0).track().getMusic().getMusicId());
        assertEquals("LUME", detail.tracks().get(0).track().getMusic().getArtist().getName());
        assertEquals("Blue Static", detail.tracks().get(0).track().getMusic().getAlbum().getTitle());
        assertEquals(Selector.USER, detail.tracks().get(0).track().getSelector());
        assertEquals(second.getMusicId(), detail.tracks().get(1).track().getMusic().getMusicId());
        assertEquals(2, detail.playlist().getMusicCount());
    }

    @Test
    @DisplayName("수록곡이 없으면 빈 목록이다")
    void returnsEmptyTracks() {
        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());

        assertTrue(detail.tracks().isEmpty());
        assertEquals(0, detail.playlist().getMusicCount());
    }

    @Test
    @DisplayName("재생 길이는 DONE 스트림에서 채워지고, 없으면 null 이다")
    void fillsDurationFromStream() {
        persistStream(first, 13020, StreamStatus.DONE);
        /* 아직 인코딩 중인 스트림은 길이를 채우지 않는다 */
        persistStream(second, 7020, StreamStatus.IN_PROGRESS);
        addTrack(first);
        addTrack(second);

        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());

        assertEquals(13020, detail.tracks().get(0).durationMs());
        assertNull(detail.tracks().get(1).durationMs());
    }

    @Test
    @DisplayName("삭제된 곡은 수록곡 목록에서 빠진다")
    void hidesDeletedMusic() {
        addTrack(first);
        addTrack(second);
        second.setIsDeleted(true);
        em.flush();

        PlaylistDetail detail = playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId());

        assertEquals(1, detail.tracks().size());
        assertEquals(first.getMusicId(), detail.tracks().get(0).track().getMusic().getMusicId());
        /* 역정규화된 곡 수는 그대로라 목록 길이와 다를 수 있다 */
        assertEquals(2, detail.playlist().getMusicCount());
    }

    @Test
    @DisplayName("남의 플레이리스트는 상세를 볼 수 없다")
    void rejectsOtherUsersPlaylistOnRead() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.getPlaylist(stranger.getUserId(), playlist.getPlaylistId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    @DisplayName("없는 플레이리스트면 404 다")
    void rejectsMissingPlaylistOnRead() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.getPlaylist(owner.getUserId(), -1));

        assertEquals(PlaylistErrorCode.PLAYLIST_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제하면 deletedAt 이 찍히고 목록·상세에서 사라진다")
    void softDeletes() {
        addTrack(first);

        playlistService.deletePlaylist(owner.getUserId(), playlist.getPlaylistId());
        em.flush();

        assertNotNull(playlist.getDeletedAt());
        assertTrue(playlistService.getPlaylistAll(owner.getUserId()).isEmpty());

        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.getPlaylist(owner.getUserId(), playlist.getPlaylistId()));
        assertEquals(PlaylistErrorCode.PLAYLIST_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제해도 수록곡 행은 남는다")
    void keepsTracksOnDelete() {
        addTrack(first);

        playlistService.deletePlaylist(owner.getUserId(), playlist.getPlaylistId());
        em.flush();

        Long tracks = em.createQuery(
                "SELECT COUNT(t) FROM PlaylistTrack t WHERE t.playlist = :playlist", Long.class)
            .setParameter("playlist", playlist)
            .getSingleResult();
        assertEquals(1L, tracks);
    }

    @Test
    @DisplayName("두 번 삭제하면 두 번째는 404 다")
    void rejectsDoubleDelete() {
        playlistService.deletePlaylist(owner.getUserId(), playlist.getPlaylistId());
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.deletePlaylist(owner.getUserId(), playlist.getPlaylistId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("남의 플레이리스트는 지울 수 없다")
    void rejectsOtherUsersPlaylistOnDelete() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.deletePlaylist(stranger.getUserId(), playlist.getPlaylistId()));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
        assertNull(playlist.getDeletedAt());
    }

    // ─────────────────────────────────────────────────────────────

    private void addTrack(Music music) {
        playlistService.addTrack(
            owner.getUserId(), playlist.getPlaylistId(), new PlaylistTrackAddRequest(music.getMusicId()));
    }

    private void persistStream(Music music, int durationMs, StreamStatus status) {
        Stream stream = Stream.builder()
            .music(music)
            .bitrateKbps(128)
            .codec(Codec.OPUS)
            .durationMs(durationMs)
            .chunkCount(1)
            .status(status)
            .build();
        em.persist(stream);
        em.flush();
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
