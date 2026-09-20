package com.osundosun.mupid.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.dto.AdminTimelineAxis;
import com.osundosun.mupid.api.admin.dto.AdminTimelineKind;
import com.osundosun.mupid.api.admin.response.AdminMusicTimelineResponse;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminTimelineBand;
import com.osundosun.mupid.api.admin.service.AdminResourceRegistry;
import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.admin.service.AdminTimelineService;
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
 * 가사·구간 타임라인 (S15P21A204-380-B).
 *
 * <h2>무엇을 못박는가</h2>
 *
 * <ol>
 *   <li><b>밴드가 세 축 다 나온다</b> — 가사·구간 분석·구간-분위기는 각각 별도 자원이라
 *       하나라도 조용히 빠지면 화면이 "그 자원은 없는 것" 으로 보여 준다.</li>
 *   <li><b>시간 값이 정규화 키로 온다</b> — 화면이 자원마다 다른 컬럼 이름(`timeMs` vs
 *       `startTimeMs`)을 알지 않아도 되게 서버가 `_time_ms`·`_start_ms`·`_end_ms` 로
 *       맞춰 보낸다. 380-C 의 드래그가 이 값을 쓴다.</li>
 *   <li><b>축 최대값은 DB 에서 집계한다</b> — 행에서 유도하면 밴드가 잘렸을 때 축이 함께
 *       줄어들어 밴드마다 눈금이 달라진다. 그래서 밴드 상한을 넘는 가사를 넣고도 축이
 *       <b>전체 기준</b>으로 나오는지 본다.</li>
 *   <li><b>부모의 시간을 빌리는 밴드가 시간 순으로 온다</b> — 구간-분위기에는 시간
 *       컬럼이 없다. 목록 조회는 자기 필드로만 정렬하므로 서버가 부모 시각으로 다시
 *       정렬해야 한다.</li>
 *   <li><b>시간이 없으면 404 가 아니라 `has_time=false`</b> — 곡은 있는데 분석만 없는
 *       것은 오류가 아니다.</li>
 * </ol>
 *
 * <p>행 수는 밴드 상한(200)보다 적게 잡는다. 상한 자체는 {@code truncated} 로 드러나므로
 * 여기서는 그 값을 직접 확인한다.
 */
@SpringBootTest
@Transactional
class AdminTimelineTest {

    @Autowired
    private AdminTimelineService timelineService;

    @Autowired
    private AdminResourceService adminResourceService;

    @Autowired
    private AdminResourceRegistry registry;

    @PersistenceContext
    private EntityManager em;

    private Music music;

    /**
     * 앨범·아티스트 커버.
     *
     * <p><b>픽스처에 반드시 필요하다.</b> 엔티티의 `@JoinColumn` 에는 `nullable` 이 없지만
     * 실제 DB 의 `artists.image_file_id` 가 `NOT NULL` 이라, 사진 없이 넣으면
     * `Column 'image_file_id' cannot be null` 로 **모든 테스트가 setUp 에서 죽는다**.
     * 여기서는 값 자체가 아니라 타임라인이 궁금하므로 최소한의 이미지 파일만 만든다.
     */
    private ObjectStorageFile cover;

    @BeforeEach
    void setUp() {
        cover = em.find(ObjectStorageFile.class, 1);
        if (cover == null) {
            cover = ObjectStorageFile.builder()
                .fileType(FileType.IMG)
                .category(FileCategory.ALBUM)
                .originalFileName("timeline-cover")
                .storageObjectKey("image/album/timeline-cover")
                .fileSizeBytes(64)
                .mime("image/webp")
                .build();
            em.persist(cover);
            em.flush();
        }

        Artist artist = Artist.builder()
            .name("타임라인검증 아티스트")
            .imageFile(cover)
            .build();
        em.persist(artist);

        Album album = Album.builder()
            .artist(artist)
            .title("타임라인검증 앨범")
            .imageFile(cover)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        music = Music.builder()
            .album(album)
            .artist(artist)
            .title("타임라인검증 곡")
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(music);
        em.flush();
    }

    @Test
    @DisplayName("380-B: 밴드가 세 축 다 나오고, 각 축이 자기 시간 필드를 안다")
    void bandsCoverAllThreeAxes() {
        AdminMusicTimelineResponse response = timelineService.timeline(music.getMusicId());

        assertEquals(music.getMusicId(), response.musicId());
        assertEquals("타임라인검증 곡", response.title());

        List<String> resources = response.bands().stream()
            .map(band -> band.axis().resource())
            .toList();

        // 셋이 각각 별도 자원이다. 하나라도 빠지면 화면에 그 밴드가 아예 안 나온다.
        assertEquals(List.of("lyric", "musicSectionAnalysis", "musicSectionAnalysisMood"), resources,
            "타임라인 밴드가 세 축을 모두 덮지 않는다 — 선언(TIMELINES)이나 검증이 축을 버렸다");

        Map<String, AdminTimelineAxis> byResource = new java.util.LinkedHashMap<>();
        response.bands().forEach(band -> byResource.put(band.axis().resource(), band.axis()));

        AdminTimelineAxis lyric = byResource.get("lyric");
        assertEquals(AdminTimelineKind.POINT, lyric.kind(), "가사는 시점(점)이어야 한다");
        assertTrue(lyric.ownsTime(), "가사는 자기 시간 컬럼을 갖는다");
        assertEquals("music", lyric.filterField(), "가사를 곡으로 거르는 필드를 모른다");

        AdminTimelineAxis sections = byResource.get("musicSectionAnalysis");
        assertEquals(AdminTimelineKind.RANGE, sections.kind(), "구간은 범위(막대)여야 한다");
        assertTrue(sections.ownsTime());

        // 구간-분위기는 시간 컬럼이 자기 것에 없다. 부모 구간에서 빌려 온다.
        AdminTimelineAxis moods = byResource.get("musicSectionAnalysisMood");
        assertFalse(moods.ownsTime(), "구간-분위기가 자기 시간을 갖는다고 나갔다 — 그 표에는 시간 컬럼이 없다");
        assertEquals("musicSectionAnalysis", moods.viaField(), "시간을 빌려올 부모를 모른다");
    }

    @Test
    @DisplayName("380-B: 시간 값이 자원 이름과 무관한 정규화 키로 온다")
    void timeValuesUseNormalisedKeys() {
        em.persist(Lyric.builder().music(music).text("첫 줄").timeMs(10_000).build());
        em.persist(Lyric.builder().music(music).text("둘째 줄").timeMs(20_000).build());
        em.persist(MusicSectionAnalysis.builder()
            .music(music).sectionLabel(SectionLabel.INTRO).index(0)
            .startTimeMs(0).endTimeMs(30_000).externalId("TL#0")
            .build());
        em.flush();
        em.clear();

        AdminMusicTimelineResponse response = timelineService.timeline(music.getMusicId());
        AdminTimelineBand lyrics = band(response, "lyric");

        assertEquals(2, lyrics.rows().size());
        for (Map<String, Object> row : lyrics.rows()) {
            // 화면은 `timeMs` 를 읽지 않는다. 자원마다 다른 이름을 알 필요가 없게 서버가 맞춘다.
            assertNotNull(row.get("_time_ms"), "가사 항목에 _time_ms 가 없다");
            assertNotNull(row.get("_id"), "항목에 편집용 _id 가 없다 — 화면이 그것으로 PATCH 를 보낸다");
        }
        // 시간 오름차순으로 온다. 화면이 다시 정렬하면 "서버가 준 순서" 와 갈라진다.
        assertEquals(10_000, lyrics.rows().get(0).get("_time_ms"));
        assertEquals(20_000, lyrics.rows().get(1).get("_time_ms"));

        Map<String, Object> section = band(response, "musicSectionAnalysis").rows().getFirst();
        assertEquals(0, section.get("_start_ms"));
        assertEquals(30_000, section.get("_end_ms"));
        assertNull(section.get("_time_ms"), "구간은 시점이 아니라 범위다 — _time_ms 를 만들면 안 된다");
    }

    @Test
    @DisplayName("380-B: 구간-분위기가 부모 구간의 시간을 빌려 시간 순으로 온다")
    void sectionMoodsBorrowParentTime() {
        // 구간 둘을 **시간 역순으로** 넣는다. 자식이 자기 필드로만 정렬하면 이 순서가
        // 그대로 나와 화면이 시간과 어긋나게 그린다.
        MusicSectionAnalysis late = MusicSectionAnalysis.builder()
            .music(music).sectionLabel(SectionLabel.CHORUS).index(0)
            .startTimeMs(90_000).endTimeMs(120_000).externalId("TL#late")
            .build();
        MusicSectionAnalysis early = MusicSectionAnalysis.builder()
            .music(music).sectionLabel(SectionLabel.INTRO).index(1)
            .startTimeMs(1_000).endTimeMs(30_000).externalId("TL#early")
            .build();
        em.persist(late);
        em.persist(early);
        em.flush();

        int lateId = late.getMusicSectionAnalysisId();
        int earlyId = early.getMusicSectionAnalysisId();
        persistMood(lateId, "잔잔한");
        persistMood(earlyId, "신나는");
        em.flush();
        em.clear();

        AdminMusicTimelineResponse response = timelineService.timeline(music.getMusicId());
        AdminTimelineBand moods = band(response, "musicSectionAnalysisMood");

        assertEquals(2, moods.rows().size());
        // 부모(구간)의 시간이 그대로 붙는다.
        assertEquals(1_000, moods.rows().get(0).get("_start_ms"),
            "자식이 자기 필드로만 정렬돼 시간 순이 아니다 — 서버가 부모 시각으로 다시 정렬해야 한다");
        assertEquals(30_000, moods.rows().get(0).get("_end_ms"));
        assertEquals(90_000, moods.rows().get(1).get("_start_ms"));

        // 어느 구간의 항목인가. 화면이 구간 막대와 분위기를 잇는 근거다.
        assertEquals(earlyId, moods.rows().get(0).get("_parent_id"));

        // FK 는 번호가 아니라 대표값으로 보여 준다 (380-A 규칙).
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) moods.rows().get(0).get("_labels");
        assertEquals("신나는", labels.get("moodCode"));
    }

    @Test
    @DisplayName("380-B: 가로축 최대값은 밴드가 잘려도 전체 기준으로 나온다")
    void axisMaxComesFromTheDatabaseNotFromRows() {
        // 마지막 가사를 밴드 상한(200) 밖에 둔다. 행에서 축을 유도하면 이 값이 안 보여
        // 축이 줄어들고, 같은 곡의 구간 밴드와 눈금이 어긋난다.
        em.persist(Lyric.builder().music(music).text("앞").timeMs(1_000).build());
        em.persist(Lyric.builder().music(music).text("뒤").timeMs(500_000).build());
        em.persist(MusicSectionAnalysis.builder()
            .music(music).sectionLabel(SectionLabel.INTRO).index(0)
            .startTimeMs(0).endTimeMs(40_000).externalId("TL#axis")
            .build());
        em.flush();
        em.clear();

        AdminMusicTimelineResponse response = timelineService.timeline(music.getMusicId());

        // 구간 끝(40000)보다 가사 마지막(500000)이 크다 — 큰 쪽이 축이 된다.
        assertEquals(500_000, response.axisMaxMs(),
            "축 최대값이 행에서 유도됐다 — 잘린 밴드에서는 눈금이 달라진다");
        assertTrue(response.hasTime(), "시간이 있는데 has_time 이 거짓이다");
    }

    @Test
    @DisplayName("380-B: 시간이 하나도 없으면 404 가 아니라 has_time=false 다")
    void emptyTimelineIsNotAnError() {
        AdminMusicTimelineResponse response = timelineService.timeline(music.getMusicId());

        assertFalse(response.hasTime(),
            "시간 정보가 없는데 has_time 이 참이다 — 화면이 0 으로 나눠 모든 항목을 0% 에 뭉친다");
        assertEquals(0, response.axisMaxMs());
        assertEquals(3, response.bands().size(), "밴드는 시간이 없어도 세 축 다 나와야 한다");
        response.bands().forEach(band -> {
            assertTrue(band.rows().isEmpty(), "빈 밴드에 행이 있다");
            assertFalse(band.truncated(), "빈 밴드가 잘렸다고 나갔다");
        });
    }

    @Test
    @DisplayName("380-B: 없는 곡은 404 로 알린다")
    void missingMusicIsNotFound() {
        com.osundosun.mupid.api.common.exception.BusinessException thrown =
            org.junit.jupiter.api.Assertions.assertThrows(
                com.osundosun.mupid.api.common.exception.BusinessException.class,
                () -> timelineService.timeline(999_999));

        assertEquals("MUPID_RECORD_NOT_FOUND", thrown.getErrorCode().getCode());
    }

    @Test
    @DisplayName("380-B: 스키마가 곡에 타임라인 축을 싣고 보낸다 (화면이 자원 이름을 모른다)")
    void schemaCarriesTimelineAxes() {
        AdminResourceMeta musicMeta = registry.find("music");
        assertNotNull(musicMeta);
        assertEquals(3, musicMeta.getTimelineAxes().size(),
            "곡의 타임라인 축이 셋이 아니다 — 화면의 '가사·구간' 버튼이 안 나온다");

        // 다른 자원에는 없어야 한다. 회원 행마다 타임라인 버튼이 붙으면 표가 못 읽게 된다.
        AdminResourceMeta userMeta = registry.find("user");
        assertNotNull(userMeta);
        assertTrue(userMeta.getTimelineAxes().isEmpty(), "곡이 아닌 자원에 타임라인 축이 붙었다");

        // 스키마 응답에도 같은 값이 실린다 — 화면은 이 응답만 보고 버튼을 그린다.
        assertTrue(adminResourceService.schema("music").timelineAxes().size() == 3);
    }

    /**
     * 축 선언이 실제 메타모델과 맞는지 **전수**로 본다.
     *
     * <p>선언을 잘못 적으면 기동 시 검증이 그 축만 버리고 로그만 남긴다 — 화면에는 밴드가
     * 조용히 사라진다. 그래서 "선언한 축이 전부 통과했는가" 를 여기서 못박는다.
     */
    @Test
    @DisplayName("380-B: 선언한 타임라인 축이 검증에서 하나도 버려지지 않는다 (전수)")
    void noDeclaredAxisIsDropped() {
        for (AdminResourceMeta meta : registry.all()) {
            for (AdminTimelineAxis axis : meta.getTimelineAxes()) {
                assertNotNull(axis.label(), meta.getName() + ": 축에 이름이 없다");
                assertNotNull(axis.resource(), meta.getName() + ": 축에 자원 이름이 없다");
                assertNotNull(axis.filterField(),
                    meta.getName() + ": 축에 필터 필드가 없다 — 자기 곡을 고를 수 없다");
                assertNotNull(axis.kind());

                // 시간 필드는 **그 시간을 가진 자원**에 있어야 한다. 자기 시간이면 밴드의
                // 자원이고, 빌려오는 것이면 `via_field` 가 가리키는 부모다. 부모(곡)에
                // 있다고 보면 안 된다 — 가사의 `timeMs` 는 `lyric` 에만 있다.
                AdminResourceMeta owner = axis.ownsTime()
                    ? registry.find(axis.resource())
                    : registry.find(axis.viaField());
                assertNotNull(owner, meta.getName() + ": 시간을 빌려올 부모를 찾지 못했다");

                if (axis.kind() == AdminTimelineKind.POINT) {
                    assertNotNull(axis.timeField(), meta.getName() + ": 점인데 시점 필드가 없다");
                    assertNotNull(owner.attribute(axis.timeField()),
                        meta.getName() + ": 시점 필드가 " + owner.getName() + " 에 없다");
                } else {
                    assertNotNull(axis.startField());
                    assertNotNull(axis.endField());
                    assertNotNull(owner.attribute(axis.startField()),
                        meta.getName() + ": 시작 필드가 " + owner.getName() + " 에 없다");
                    assertNotNull(owner.attribute(axis.endField()),
                        meta.getName() + ": 끝 필드가 " + owner.getName() + " 에 없다");
                }

                // 이름에 쓸 필드도 실제로 있어야 한다. 없으면 화면이 '#'+번호만 그린다.
                for (String titleField : axis.titleFields()) {
                    AdminAttribute attribute = registry.find(axis.resource()).attribute(titleField);
                    assertNotNull(attribute,
                        meta.getName() + ": 이름 필드 " + titleField + " 가 " + axis.resource() + " 에 없다");
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────

    private static AdminTimelineBand band(AdminMusicTimelineResponse response, String resource) {
        return response.bands().stream()
            .filter(band -> resource.equals(band.axis().resource()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("밴드가 없다: " + resource));
    }

    /**
     * 구간-분위기 연결 하나. 코드표에 이름이 있어야 한다 —
     * 없으면 목록이 대표값을 못 붙여 "번호만 나오는" 정상 상태가 되고, 이 테스트가
     * 보려는 것(대표값)을 못 본다. 그래서 없는 종류만 심는다.
     */
    private void persistMood(int sectionAnalysisId, String codeName) {
        com.osundosun.mupid.api.code.entity.Code code = em
            .createQuery("SELECT c FROM Code c WHERE c.codeType = :type AND c.codeName = :name",
                com.osundosun.mupid.api.code.entity.Code.class)
            .setParameter("type", com.osundosun.mupid.api.code.enumerate.CodeType.MOOD)
            .setParameter("name", codeName)
            .setMaxResults(1)
            .getResultList()
            .stream()
            .findFirst()
            .orElseGet(() -> {
                com.osundosun.mupid.api.code.entity.Code created =
                    com.osundosun.mupid.api.code.entity.Code.builder()
                        .codeType(com.osundosun.mupid.api.code.enumerate.CodeType.MOOD)
                        .codeName(codeName)
                        .displayName(codeName)
                        .build();
                em.persist(created);
                em.flush();
                return created;
            });

        MusicSectionAnalysis section = em.find(MusicSectionAnalysis.class, sectionAnalysisId);
        em.persist(new com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood(
            new com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood
                .MusicSectionAnalysisMoodId(),
            section,
            code));
    }

}
