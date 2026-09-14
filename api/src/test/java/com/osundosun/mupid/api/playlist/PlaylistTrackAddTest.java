package com.osundosun.mupid.api.playlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.osundosun.mupid.api.playlist.dto.PlaylistTrackAddResult;
import com.osundosun.mupid.api.playlist.entity.Playlist;
import com.osundosun.mupid.api.playlist.error.PlaylistErrorCode;
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
 * 곡 추가 API 의 서비스 계층 검증.
 *
 * 로컬 MySQL 의 실제 스키마를 그대로 쓴다 — `playlist_tracks` 의 복합키·유니크 제약과
 * `selector` ENUM 매핑은 인메모리 DB 로는 검증되지 않는다. 트랜잭션은 테스트마다
 * 롤백되므로 데이터는 남지 않는다 (AUTO_INCREMENT 만 올라간다).
 */
@SpringBootTest
@Transactional
class PlaylistTrackAddTest {

    @Autowired
    private PlaylistService playlistService;

    @PersistenceContext
    private EntityManager em;

    private User owner;
    private User stranger;
    private Playlist playlist;
    private Music music;
    private Music anotherMusic;

    @BeforeEach
    void setUp() {
        owner = persistUser();
        stranger = persistUser();

        /* albums·artists 는 이미지 파일이 필수다 (image_file_id NOT NULL) */
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

        music = persistMusic(album, artist, "푸른 오후");
        anotherMusic = persistMusic(album, artist, "새벽 드라이브");

        playlist = Playlist.builder().user(owner).title("테스트 플레이리스트").build();
        em.persist(playlist);

        em.flush();
    }

    @Test
    @DisplayName("새 곡을 추가하면 마지막 순서로 담기고 수록곡 수가 오른다")
    void addsNewTrack() {
        PlaylistTrackAddResult result = addTrack(owner, playlist, music);

        assertFalse(result.duplicated());
        assertEquals(1, result.track().getTrackOrder());
        assertEquals(music.getMusicId(), result.track().getMusic().getMusicId());
        assertEquals(1, playlist.getMusicCount());
    }

    @Test
    @DisplayName("곡을 여러 개 추가하면 트랙 순서가 이어진다")
    void appendsTrackOrder() {
        addTrack(owner, playlist, music);
        PlaylistTrackAddResult second = addTrack(owner, playlist, anotherMusic);

        assertEquals(2, second.track().getTrackOrder());
        assertEquals(2, playlist.getMusicCount());
    }

    @Test
    @DisplayName("이미 담긴 곡은 오류가 아니라 중복으로 표시되고 아무것도 바뀌지 않는다")
    void marksDuplicateInsteadOfFailing() {
        PlaylistTrackAddResult first = addTrack(owner, playlist, music);
        PlaylistTrackAddResult second = addTrack(owner, playlist, music);

        assertTrue(second.duplicated());
        /* 기존 수록곡을 그대로 돌려준다 */
        assertEquals(first.track().getTrackOrder(), second.track().getTrackOrder());
        assertEquals(first.track().getCreatedAt(), second.track().getCreatedAt());
        /* 수록곡 수는 그대로다 */
        assertEquals(1, playlist.getMusicCount());
        assertEquals(1L, countTracks());
    }

    @Test
    @DisplayName("남의 플레이리스트에는 곡을 넣을 수 없다")
    void rejectsOtherUsersPlaylist() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> addTrack(stranger, playlist, music));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    @DisplayName("소유자가 없는 플레이리스트에는 곡을 넣을 수 없다")
    void rejectsSystemPlaylist() {
        Playlist systemPlaylist = Playlist.builder().user(null).title("추천").build();
        em.persist(systemPlaylist);
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> addTrack(owner, systemPlaylist, music));

        assertEquals(PlaylistErrorCode.PLAYLIST_ACCESS_DENIED, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제된 플레이리스트는 없는 것으로 본다")
    void rejectsDeletedPlaylist() {
        playlist.setDeletedAt(LocalDateTime.now());
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> addTrack(owner, playlist, music));

        assertEquals(PlaylistErrorCode.PLAYLIST_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("없는 플레이리스트면 404 다")
    void rejectsMissingPlaylist() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> playlistService.addTrack(
                owner.getUserId(), -1, new PlaylistTrackAddRequest(music.getMusicId())));

        assertEquals(PlaylistErrorCode.PLAYLIST_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제된 곡은 담을 수 없다")
    void rejectsDeletedMusic() {
        music.setIsDeleted(true);
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> addTrack(owner, playlist, music));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, e.getErrorCode());
    }

    // ─────────────────────────────────────────────────────────────

    private PlaylistTrackAddResult addTrack(User user, Playlist target, Music target_music) {
        return playlistService.addTrack(
            user.getUserId(),
            target.getPlaylistId(),
            new PlaylistTrackAddRequest(target_music.getMusicId()));
    }

    private long countTracks() {
        return em.createQuery(
                "SELECT COUNT(t) FROM PlaylistTrack t WHERE t.playlist = :playlist", Long.class)
            .setParameter("playlist", playlist)
            .getSingleResult();
    }

    private User persistUser() {
        User user = User.builder()
            /* 이메일이 유니크라 테스트끼리 부딪히지 않게 매번 새로 만든다 */
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
        Music newMusic = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(newMusic);
        return newMusic;
    }

}
