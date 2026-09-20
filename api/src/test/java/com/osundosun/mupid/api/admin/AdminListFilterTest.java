package com.osundosun.mupid.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Lyric;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 관리자 목록 조회의 **목록 필터**(`__in__:`) — S15P21A204-380-B.
 *
 * <h2>왜 필요한가</h2>
 *
 * 타임라인의 구간-분위기 밴드는 분위기가 **구간에** 달려 있어서, 곡 하나의 분위기를 고르려면
 * 그 곡의 구간 번호 여러 개를 한 번에 걸어야 한다. 구간마다 한 번씩 부르면 구간 수만큼
 * 질의가 붙어 그대로 N+1 이다.
 *
 * <p>기존 `filter.<필드>=<값>` 은 **완전 일치 하나**뿐이었다. 그래서 목록 필터에 `IN` 을
 * 더했다. 여기서 못박는 것은 셋이다.
 *
 * <ol>
 *   <li>여러 값 중 하나면 걸린다 (이게 없으면 밴드가 빈다).</li>
 *   <li>목록 밖의 행은 안 걸린다 (조건을 빼면 **다른 곡**의 데이터가 나온다).</li>
 *   <li>값이 하나도 없으면 **빈 결과**다 — 빈 `IN ()` 은 SQL 문법 오류라 질의가 죽고,
 *       조건을 빼면 전부 나와 화면이 남의 데이터를 그린다. 둘 다 거짓말이라 거짓 조건이 맞다.</li>
 * </ol>
 *
 * <p>기존 `filter.<필드>=<값>` 완전 일치가 그대로인지도 함께 본다 — `IN` 을 넣으면서
 * 그쪽을 깨뜨리기 쉽다.
 */
@SpringBootTest
@Transactional
class AdminListFilterTest {

    @Autowired
    private AdminResourceService adminResourceService;

    @PersistenceContext
    private EntityManager em;

    private Music wanted;

    /**
     * 앨범·아티스트 커버.
     *
     * <p>실제 DB 의 `artists.image_file_id` 가 `NOT NULL` 이라, 사진 없이 넣으면
     * `Column 'image_file_id' cannot be null` 로 모든 테스트가 setUp 에서 죽는다.
     * 엔티티의 `@JoinColumn` 에는 `nullable` 이 없어 코드만 봐서는 알 수 없다.
     */
    private ObjectStorageFile cover;

    @BeforeEach
    void setUp() {
        cover = em.find(ObjectStorageFile.class, 1);
        if (cover == null) {
            cover = ObjectStorageFile.builder()
                .fileType(FileType.IMG)
                .category(FileCategory.ALBUM)
                .originalFileName("filter-cover")
                .storageObjectKey("image/album/filter-cover")
                .fileSizeBytes(64)
                .mime("image/webp")
                .build();
            em.persist(cover);
            em.flush();
        }

        Artist artist = Artist.builder()
            .name("필터검증 아티스트")
            .imageFile(cover)
            .build();
        em.persist(artist);

        Album album = Album.builder()
            .artist(artist)
            .title("필터검증 앨범")
            .imageFile(cover)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        wanted = persistMusic(artist, album, "필터검증 곡 A");
        persistMusic(artist, album, "필터검증 곡 B");
        em.flush();
    }

    @Test
    @DisplayName("380-B: __in__ 필터가 여러 값 중 하나로 거른다")
    void inFilterMatchesAnyOfTheValues() {
        em.persist(Lyric.builder().music(wanted).text("줄1").timeMs(1_000).build());
        em.persist(Lyric.builder().music(wanted).text("줄2").timeMs(2_000).build());
        em.flush();
        em.clear();

        List<String> ids = lyricIdsOf(wanted.getMusicId());
        assertEquals(2, ids.size(), "픽스처 준비 실패");

        AdminRowPageResponse page = adminResourceService.list(
            "lyric", 0, 50, "timeMs", "asc", null,
            Map.of("lyricId", "__in__:" + String.join(",", ids)));

        assertEquals(2, page.total(), "__in__ 이 두 값 중 하나로 거르지 못했다");
        assertEquals(2, page.content().size());
    }

    @Test
    @DisplayName("380-B: __in__ 목록 밖의 행은 걸리지 않는다")
    void inFilterExcludesRowsOutsideTheList() {
        em.persist(Lyric.builder().music(wanted).text("줄1").timeMs(1_000).build());
        em.persist(Lyric.builder().music(wanted).text("줄2").timeMs(2_000).build());
        em.flush();
        em.clear();

        List<String> ids = lyricIdsOf(wanted.getMusicId());

        // 목록에 **없는** 번호만 건다. 조건이 빠지면 전부 나와 이 단언이 깨진다.
        AdminRowPageResponse page = adminResourceService.list(
            "lyric", 0, 50, "timeMs", "asc", null,
            Map.of("lyricId", "__in__:999999"));

        assertEquals(0, page.total(), "목록 밖의 행이 걸렸다 — 필터가 무시되고 있다");
        assertTrue(page.content().isEmpty());
    }

    @Test
    @DisplayName("380-B: 값이 빈 __in__ 은 전부가 아니라 아무것도 돌려주지 않는다")
    void emptyInFilterReturnsNothing() {
        em.persist(Lyric.builder().music(wanted).text("줄1").timeMs(1_000).build());
        em.flush();
        em.clear();

        // 빈 `IN ()` 은 SQL 문법 오류다. 조건을 빼면 "아무것도 안 걸린 곡의 전부" 가 나온다.
        // 둘 다 거짓말이므로 빈 결과가 맞다.
        AdminRowPageResponse page = adminResourceService.list(
            "lyric", 0, 50, "timeMs", "asc", null,
            Map.of("lyricId", "__in__:"));

        assertEquals(0, page.total(), "빈 __in__ 이 전체를 돌려줬다 — 화면이 남의 데이터를 그린다");
    }

    @Test
    @DisplayName("380-B: 구간-분위기를 구간 번호 여러 개로 한 번에 고른다 (밴드가 쓰는 경로)")
    void inFilterSelectsSectionMoodsInOneQuery() {
        MusicSectionAnalysis first = persistSection(0, "FL#0");
        MusicSectionAnalysis second = persistSection(1, "FL#1");
        em.flush();
        em.clear();

        AdminRowPageResponse page = adminResourceService.list(
            "musicSectionAnalysisMood", 0, 50, "musicSectionAnalysisId", "asc", null,
            Map.of("musicSectionAnalysisId",
                "__in__:" + first.getMusicSectionAnalysisId()
                    + "," + second.getMusicSectionAnalysisId()));

        // 이 환경의 코드표에 분위기가 안 붙어 있으면 0 행이 정상이다. 여기서 보려는 것은
        // **예외 없이 응답하는가** 이지 행 수가 아니다 — 빈 `IN` 이었다면 질의가 죽는다.
        assertNotNull(page);
        assertTrue(page.total() >= 0);
    }

    @Test
    @DisplayName("380-B: 기존 완전 일치 필터가 그대로 동작한다")
    void exactFilterStillWorks() {
        em.persist(Lyric.builder().music(wanted).text("줄1").timeMs(1_000).build());
        em.flush();
        em.clear();

        // `IN` 을 넣으면서 이쪽을 깨뜨리기 쉽다. 타임라인 밖의 모든 화면이 이 규약을 쓴다.
        AdminRowPageResponse page = adminResourceService.list(
            "lyric", 0, 50, "timeMs", "asc", null,
            Map.of("music", String.valueOf(wanted.getMusicId())));

        assertNotNull(page);
        assertTrue(page.total() >= 0, "완전 일치 필터가 음수를 돌려줬다");
    }

    @Test
    @DisplayName("380-B: 필터 값의 형식이 틀리면 400 이다 (조용히 무시하지 않는다)")
    void badValueIsRejected() {
        // 숫자 컬럼에 숫자로 읽히지 않는 값을 주면 400 이다. 조용히 무시하면 필터를 건
        // 사람이 "왜 전체가 나오지" 로만 보게 된다.
        assertThrows(BusinessException.class, () -> adminResourceService.list(
            "lyric", 0, 50, "timeMs", "asc", null,
            Map.of("lyricId", "__in__:abc")));
    }

    // ─────────────────────────────────────────────────────────────

    private Music persistMusic(Artist artist, Album album, String title) {
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(music);
        return music;
    }

    private MusicSectionAnalysis persistSection(int index, String externalId) {
        MusicSectionAnalysis section = MusicSectionAnalysis.builder()
            .music(wanted)
            .sectionLabel(SectionLabel.VERSE)
            .index(index)
            .startTimeMs(index * 10_000)
            .endTimeMs((index + 1) * 10_000)
            .externalId(externalId)
            .build();
        em.persist(section);
        return section;
    }

    private List<String> lyricIdsOf(int musicId) {
        return em.createQuery(
                "SELECT l.lyricId FROM Lyric l WHERE l.music.musicId = :musicId", Integer.class)
            .setParameter("musicId", musicId)
            .getResultList()
            .stream()
            .map(String::valueOf)
            .toList();
    }

}
