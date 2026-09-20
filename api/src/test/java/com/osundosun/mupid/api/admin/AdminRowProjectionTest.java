package com.osundosun.mupid.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminThumbnail;
import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
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
 * 관리자 목록 조회가 **FK 대표값·썸네일을 붙이면서도 N+1 을 내지 않는지** 실제 질의 수로
 * 못박는다.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * 380-A 의 요구는 "FK 를 대표 컬럼 값과 함께 보여 준다" 이다. 순진하게 구현하면 행마다
 * "이 번호가 가리키는 것의 이름이 뭐냐" 를 물어보게 되고, 그 순간 목록 한 화면이 행 수만큼
 * 질의를 낸다. 관리자 화면은 `music` 이 이미 1000행을 넘고 자원이 서른 개라, 목록이 수백
 * 행일 때 죽는다.
 *
 * <p>그래서 값은 **행을 가져오는 그 질의 안에서** LEFT JOIN 을 타고 같이 가져온다. 이
 * 테스트는 그 계약을 질의 수로 확인한다 — 행 수를 늘려도 질의 수가 그대로여야 한다.
 *
 * <h2>Hibernate Statistics 를 쓰는 이유</h2>
 *
 * SQL 로그를 세는 방법은 로그 형식에 기대게 되고, 지연 로딩이 일어났는지도 구분이 안 된다.
 * `Statistics` 는 준비된 statement 수를 직접 세므로 "행마다 하나씩 늘었는가" 가 그대로
 * 보인다.
 */
@SpringBootTest
@Transactional
class AdminRowProjectionTest {

    private static final String ALBUM = "album";
    private static final String USER = "user";

    @Autowired
    private AdminResourceService adminResourceService;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SessionFactory sessionFactory;

    private Artist artist;
    private ObjectStorageFile albumCover;

    /**
     * 픽스처를 직접 만든다. 시드 데이터에 기대면 그것이 바뀔 때 테스트가 조용히 의미를
     * 잃는다 — 여기서 보려는 것은 "행 수 대비 질의 수" 이지 특정 행의 내용이 아니다.
     */
    @BeforeEach
    void setUp() {
        albumCover = persistImage(FileCategory.ALBUM, "projection-cover");

        // 아티스트에게 사진을 준다. 앨범 행에서 **한 단계 더 들어가** 읽는 값이라
        // 그것도 같은 질의 안에서 오는지 확인 대상이다.
        artist = Artist.builder()
            .name("투영검증 아티스트")
            .imageFile(persistImage(FileCategory.ARTIST, "projection-artist"))
            .build();
        em.persist(artist);
        em.flush();
    }

    @Test
    @DisplayName("목록: FK 대표값은 행 수와 무관하게 같은 질의 수로 가져온다")
    void labelsDoNotMultiplyQueries() {
        // 앨범을 여러 개 만든다. 각 앨범은 아티스트(FK)를 가리키므로, 순진한 구현이면
        // 앨범마다 아티스트를 한 번씩 더 읽는다.
        for (int index = 0; index < 12; index++) {
            em.persist(Album.builder()
                .artist(artist)
                .title("투영검증 앨범 " + index)
                .imageFile(albumCover)
                .releaseDate(LocalDateTime.now())
                .build());
        }
        em.flush();
        em.clear();

        long threeRows = countStatements(() ->
            adminResourceService.list(ALBUM, 0, 3, "albumId", "desc", null, Map.of()));

        em.clear();
        long twelveRows = countStatements(() ->
            adminResourceService.list(ALBUM, 0, 12, "albumId", "desc", null, Map.of()));

        // 실행한 질의 수를 남긴다. "N+1 을 피했다" 는 주장의 근거가 이 숫자다.
        System.out.printf("PROBE_QUERY_COUNT album rows=3 statements=%d rows=12 statements=%d%n",
            threeRows, twelveRows);

        // 행이 3 → 12 로 늘어도 질의 수는 그대로여야 한다. 목록 1 + COUNT 1 이다.
        assertEquals(threeRows, twelveRows,
            "행 수를 늘렸는데 질의가 늘었다 — 행마다 조회하는 N+1 이 생겼다 (3행="
                + threeRows + ", 12행=" + twelveRows + ")");

        // 2 를 넘으면 목록·COUNT 외의 질의가 섞였다는 뜻이다. 여유를 두지 않고 못박는다.
        assertTrue(twelveRows <= 2,
            "목록 조회가 질의 " + twelveRows + "개를 냈다 — 목록 1 + COUNT 1 을 넘었다");
    }

    @Test
    @DisplayName("목록: 대표값과 번호가 함께 오고, 대표값은 대상의 컬럼에서 읽는다")
    void labelComesFromTheTargetColumn() {
        em.persist(Album.builder()
            .artist(artist)
            .title("대표값 검증 앨범")
            .imageFile(albumCover)
            .releaseDate(LocalDateTime.now())
            .build());
        em.flush();
        em.clear();

        Map<String, Object> row = adminResourceService
            .list(ALBUM, 0, 1, "albumId", "desc", null, Map.of())
            .content().get(0);

        // 번호는 남는다 — 요구가 "대표값과 번호를 함께" 다.
        assertNotNull(row.get("artist"), "FK 번호가 사라졌다");

        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) row.get("_labels");
        assertNotNull(labels, "FK 대표값(_labels)이 응답에 없다");
        assertEquals(artist.getName(), labels.get("artist"),
            "대표값이 아티스트 이름이 아니라 다른 컬럼에서 왔다");
    }

    @Test
    @DisplayName("목록: 썸네일은 파일 번호 주소로 오고, 공개 여부를 서버가 판단해 함께 준다")
    void thumbnailsCarryUrlAndAuthFlag() {
        em.persist(Album.builder()
            .artist(artist)
            .title("썸네일 검증 앨범")
            .imageFile(albumCover)
            .releaseDate(LocalDateTime.now())
            .build());
        em.flush();
        em.clear();

        Map<String, Object> row = adminResourceService
            .list(ALBUM, 0, 1, "albumId", "desc", null, Map.of())
            .content().get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> thumbnails = (Map<String, Object>) row.get("_thumbnails");
        assertNotNull(thumbnails, "썸네일(_thumbnails)이 응답에 없다");

        AdminThumbnail cover = (AdminThumbnail) thumbnails.get("imageFile");
        assertNotNull(cover, "앨범 표지 썸네일이 없다 — 대상이 파일이면 조인한 행에서 바로 읽어야 한다");

        // 파일 번호로 만든 주소여야 한다. 저장 키를 되짚으면 키 모양이 어긋날 때 조용히 빈다.
        assertTrue(cover.url().contains("/api/v1/storage/image/"),
            "썸네일 주소가 파일 번호 주소가 아니다: " + cover.url());
        // 앨범 표지는 공개 카테고리다 (ImageAccessPolicy.CATALOG). 화면이 <img src> 로
        // 바로 걸 수 있어야 하므로 인증이 필요하면 안 된다.
        assertEquals(false, cover.requiresAuthorization(),
            "공개 카테고리인데 인증이 필요하다고 나갔다 — 화면이 토큰 없이 받아 빈 자리를 그린다");
    }

    @Test
    @DisplayName("목록: 이미지가 아닌 파일은 썸네일로 내보내지 않는다")
    void nonImageFileIsNotAThumbnail() {
        // 음원 청크가 표지 자리로 나가면 화면이 <img src> 에 걸고 400 을 받아 조용히 깨진다.
        // StorageImageService.getImageData 와 **같은 검사**를 목록에서도 한다.
        ObjectStorageFile chunk = ObjectStorageFile.builder()
            .fileType(FileType.MUSIC)
            .category(FileCategory.STREAMING)
            .originalFileName("chunk.rtp")
            .storageObjectKey("music/1/chunk.rtp")
            .fileSizeBytes(1024)
            .mime("audio/rtp")
            .build();
        em.persist(chunk);

        // 앨범을 먼저 persist 해야 한다. transient 인 앨범을 곡이 참조하면 flush 에서
        // TransientPropertyValueException 이 난다.
        Album album = Album.builder()
            .artist(artist)
            .title("이미지 아님 검증 앨범")
            .imageFile(albumCover)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title("이미지 아님 검증 곡")
            .releaseDate(LocalDateTime.now())
            .imageFile(chunk)
            .build();
        em.persist(music);
        em.flush();
        em.clear();

        Map<String, Object> row = adminResourceService
            .list("music", 0, 1, "musicId", "desc", null, Map.of())
            .content().get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> thumbnails = row.get("_thumbnails") instanceof Map<?, ?> found
            ? (Map<String, Object>) found
            : Map.of();

        assertNull(thumbnails.get("imageFile"),
            "이미지가 아닌 파일이 썸네일로 나갔다 — 화면이 깨진 그림 자리를 그리게 된다");
    }

    @Test
    @DisplayName("단건 조회도 목록과 같은 대표값·썸네일 규칙을 쓴다")
    void singleRowUsesTheSameRule() {
        Album album = Album.builder()
            .artist(artist)
            .title("단건 검증 앨범")
            .imageFile(albumCover)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);
        em.flush();
        em.clear();

        Map<String, Object> row = adminResourceService.get(ALBUM, String.valueOf(album.getAlbumId()));

        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) row.get("_labels");
        assertNotNull(labels, "단건 조회에는 대표값이 없다 — 화면이 목록과 갈라진다");
        assertEquals(artist.getName(), labels.get("artist"));
    }

    @Test
    @DisplayName("회원·앨범·아티스트 썸네일이 모두 파일 번호 주소로 나온다")
    void everyRequestedResourceHasThumbnails() {
        ObjectStorageFile profile = persistImage(FileCategory.PROFILE, "projection-profile");
        User user = User.builder()
            .email("projection-thumb@mupid.test")
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("투영검증")
            .imageFile(profile)
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        em.flush();
        em.clear();

        Map<String, Object> row = adminResourceService
            .list(USER, 0, 1, "userId", "desc", null, Map.of())
            .content().get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> thumbnails = (Map<String, Object>) row.get("_thumbnails");
        assertNotNull(thumbnails, "회원 프로필 썸네일이 없다");

        AdminThumbnail image = (AdminThumbnail) thumbnails.get("imageFile");
        assertNotNull(image, "회원 프로필 사진 자리가 비었다");
        // 프로필은 비공개 카테고리다. 화면은 이 값이 참일 때만 토큰을 붙여 받는다.
        assertTrue(image.requiresAuthorization(),
            "프로필은 비공개인데 인증이 필요 없다고 나갔다 — 화면이 401 을 받아 빈 자리를 그린다");
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 한 블록 동안 Hibernate 가 준비한 statement 수.
     *
     * 세션 단위로 세지 않고 전역 통계를 비우고 재는 것은, 지연 로딩이 트랜잭션 경계 밖에서
     * 일어나도 세어지게 하려는 것이다.
     */
    private long countStatements(Runnable block) {
        Statistics statistics = sessionFactory.getStatistics();
        // 기본값은 꺼짐이다. 켜지 않으면 getPrepareStatementCount 가 언제나 0 을 돌려주고,
        // 그러면 "행이 늘어도 질의가 안 늘었다" 는 단언이 아무것도 안 보는 채로 통과한다.
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        block.run();
        return statistics.getPrepareStatementCount();
    }

    private ObjectStorageFile persistImage(FileCategory category, String key) {
        ObjectStorageFile file = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(category)
            .originalFileName(key)
            .storageObjectKey("image/%s/%s".formatted(category.name().toLowerCase(), key))
            .fileSizeBytes(64)
            .mime("image/webp")
            .build();
        em.persist(file);
        em.flush();
        return file;
    }

}
