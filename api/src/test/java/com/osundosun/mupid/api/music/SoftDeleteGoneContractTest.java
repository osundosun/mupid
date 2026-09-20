package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 소프트 삭제된 자원이 <b>410 Gone</b> 으로 나가는지 <b>HTTP 로</b> 확인한다.
 *
 * <h2>왜 404 가 아니라 410 인가</h2>
 *
 * 이 서비스는 삭제를 소프트 삭제로 한다 — 행이 남는다
 * ({@code knowledge/decision/0013-soft-delete-policy.md}). 그래서 "그런 자원이 없다" 와
 * "있었는데 지워졌다" 를 구분할 수 있고, 보낸 쪽이 할 일이 다르다. 앞은 번호를 고쳐야
 * 하고, 뒤는 그 자원을 포기해야 한다. 전처리 배치는 이 구분이 없어서 삭제된 곡을
 * 404 로만 알았고, 곡 목록을 통째로 훑어 우회해야 했다(S15P21A204-399).
 *
 * <h2>서비스 계층이 아니라 HTTP 로 보는 이유</h2>
 *
 * 상태코드는 <b>오류 코드 enum</b> 이 소유하고
 * ({@code knowledge/decision/0003-error-code-enum-owns-http-status.md}) 핸들러가 그것을
 * 그대로 내보낸다. enum 과 핸들러 사이가 끊기면 서비스 테스트는 통과하는데 응답은
 * 500 이 되는 일이 생긴다 — 여기서 {@code status().isGone()} 과
 * {@code X-Mupid-Error} 헤더를 함께 못박는다.
 *
 * <p>곡 카탈로그는 `SecurityConfig` 의 `anyRequest` 로 닫혀 있다 — 인증 없이 부르면
 * 401 이라 상태코드를 볼 수 없다. 그래서 로그인한 사용자를 붙여 부른다.
 *
 * <p>테스트 트랜잭션은 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SoftDeleteGoneContractTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private Artist artist;
    private Album album;

    @BeforeEach
    void setUp() {
        ObjectStorageFile image = persistImage();
        artist = Artist.builder().name("LUME-" + tag()).imageFile(image).build();
        em.persist(artist);
        album = Album.builder()
            .artist(artist).title("Blue Static-" + tag()).imageFile(image)
            .releaseDate(LocalDateTime.now()).build();
        em.persist(album);
        music = Music.builder()
            .album(album).artist(artist).title("푸른 오후-" + tag())
            .releaseDate(LocalDateTime.now()).build();
        em.persist(music);
        em.flush();
    }

    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("삭제된 곡 상세 조회는 410 이다 — 404 가 아니다")
    void deletedMusicDetailIsGone() throws Exception {
        music.setIsDeleted(true);
        em.flush();

        mockMvc.perform(get("/api/v1/music/{musicId}", music.getMusicId())
                .with(authentication(token())))
            .andExpect(status().isGone())
            .andExpect(header().string("X-Mupid-Error", "MUPID_MUSIC_GONE"))
            .andExpect(jsonPath("$.code").value("MUPID_MUSIC_GONE"));
    }

    @Test
    @DisplayName("없는 곡은 그대로 404 다 — 이 구분이 요점이다")
    void missingMusicStaysNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/music/{musicId}", Integer.MAX_VALUE)
                .with(authentication(token())))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Mupid-Error", "MUPID_MUSIC_NOT_FOUND"));
    }

    @Test
    @DisplayName("살아 있는 곡은 200 이다 — 가드가 정상 경로를 막지 않는다")
    void livingMusicIsServed() throws Exception {
        mockMvc.perform(get("/api/v1/music/{musicId}", music.getMusicId())
                .with(authentication(token())))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("삭제된 곡의 분석 조회도 410 이다")
    void deletedMusicAnalysisIsGone() throws Exception {
        music.setIsDeleted(true);
        em.flush();

        mockMvc.perform(get("/api/v1/music/{musicId}/analysis", music.getMusicId())
                .with(authentication(token())))
            .andExpect(status().isGone())
            .andExpect(header().string("X-Mupid-Error", "MUPID_MUSIC_GONE"));
    }

    @Test
    @DisplayName("삭제된 아티스트 상세 조회는 410 이다")
    void deletedArtistDetailIsGone() throws Exception {
        artist.setIsDeleted(true);
        em.flush();

        mockMvc.perform(get("/api/v1/artist/{artistId}", artist.getArtistId())
                .with(authentication(token())))
            .andExpect(status().isGone())
            .andExpect(header().string("X-Mupid-Error", "MUPID_ARTIST_GONE"));
    }

    @Test
    @DisplayName("삭제된 앨범 상세 조회는 410 이다")
    void deletedAlbumDetailIsGone() throws Exception {
        album.setIsDeleted(true);
        em.flush();

        mockMvc.perform(get("/api/v1/album/{albumId}", album.getAlbumId())
                .with(authentication(token())))
            .andExpect(status().isGone())
            .andExpect(header().string("X-Mupid-Error", "MUPID_ALBUM_GONE"));
    }

    @Test
    @DisplayName("삭제된 곡의 스트림 조회는 곡 Gone 으로 답한다 — 스트림 404 가 아니다")
    void deletedMusicStreamReportsMusicGone() throws Exception {
        /*
         * 부모 곡이 지워졌는데 `STREAM_NOT_FOUND` 를 주면 보낸 쪽은 스트림 번호를
         * 고치려 든다. 실제로는 곡이 사라진 것이라 그 곡을 포기해야 한다.
         */
        music.setIsDeleted(true);
        em.flush();

        mockMvc.perform(get("/api/v1/music/{musicId}/stream", music.getMusicId())
                .with(authentication(token())))
            .andExpect(status().isGone())
            .andExpect(header().string("X-Mupid-Error", "MUPID_MUSIC_GONE"));
    }

    /** enum 이 실제로 410 을 들고 있는지 — 핸들러를 거치지 않고 직접 본다. */
    @Test
    @DisplayName("모든 *_GONE 코드가 GONE(410) 이다")
    void everyGoneCodeIsHttp410() {
        assertEquals(HttpStatus.GONE, MusicErrorCode.MUSIC_GONE.getStatus());
        assertEquals(HttpStatus.GONE, ArtistErrorCode.ARTIST_GONE.getStatus());
        assertEquals(HttpStatus.GONE,
            com.osundosun.mupid.api.music.error.AlbumErrorCode.ALBUM_GONE.getStatus());
        assertEquals(HttpStatus.GONE,
            com.osundosun.mupid.api.music.error.MusicCommentErrorCode.COMMENT_GONE.getStatus());
        assertEquals(HttpStatus.GONE,
            com.osundosun.mupid.api.playlist.error.PlaylistErrorCode.PLAYLIST_GONE.getStatus());
        assertEquals(HttpStatus.GONE,
            com.osundosun.mupid.api.user.error.UserErrorCode.USER_GONE.getStatus());
    }

    // ─────────────────────────────────────────────────────────────

    /** 로그인한 사용자. 이 API 들은 인증이 필요하다(`SecurityConfig` 의 anyRequest). */
    private static UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken("probe@mupid.test", null, List.of());
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
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

}
