package com.osundosun.mupid.api.ai.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.controller.AiCatalogController;
import com.osundosun.mupid.api.ai.response.AiCatalogPageResponse;
import com.osundosun.mupid.api.ai.response.AiCatalogSectionResponse;
import com.osundosun.mupid.api.ai.response.AiCatalogTrackResponse;
import com.osundosun.mupid.api.ai.service.AiCatalogService;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.enumerate.VocalGender;
import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;
import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;
import com.osundosun.mupid.api.music.mapping.entity.MusicMood;
import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * AI 전용 카탈로그 조회가 <b>추천이 실제로 읽는 값</b>을 빠짐없이, 그리고 삭제된 곡은
 * 빼고 내보내는지 못박는다.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * 추천 서버가 제 색인 안에 들고 있던 장르·무드·보컬·악기 사본을 없애고 이 응답만
 * 보게 됐다. 그래서 이 계약에서 한 축이 빠지면 <b>추천에서 그 조건이 통째로
 * 사라진다</b> — 오류가 아니라 "조건을 걸었는데 아무 곡도 안 걸러진" 결과로 나오고,
 * 응답만 봐서는 정상과 구분되지 않는다. 축마다 값이 실제로 실리는지 확인한다.
 *
 * <p>픽스처를 직접 만든다. 시드에 기대면 시드가 바뀔 때 이 테스트가 조용히 의미를
 * 잃는다.
 */
@SpringBootTest
@Transactional
class AiCatalogServiceTest {

    private static final String MARK = "AI카탈로그시험";
    /** 픽스처를 찾을 때 쓰는 페이지 크기. 컨트롤러 상한 안이어야 한다. */
    private static final int SCAN_SIZE = 200;

    @Autowired
    private AiCatalogService aiCatalogService;

    @Autowired
    private AiCatalogController aiCatalogController;

    @PersistenceContext
    private EntityManager em;

    private Album album;
    private Code genreCode;
    private Code moodCode;
    private Code sectionMoodCode;
    private Code instrumentCode;

    @BeforeEach
    void setUp() {
        ObjectStorageFile cover = persistImage(FileCategory.ALBUM, "ai-catalog-cover");
        Artist artist = Artist.builder()
            .name(MARK + " 아티스트")
            .imageFile(persistImage(FileCategory.ARTIST, "ai-catalog-artist"))
            .build();
        em.persist(artist);
        album = Album.builder()
            .artist(artist)
            .title(MARK + " 앨범")
            .imageFile(cover)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        genreCode = persistCode(CodeType.GENRE, MARK + "_genre");
        moodCode = persistCode(CodeType.MOOD, MARK + "_mood");
        sectionMoodCode = persistCode(CodeType.MOOD, MARK + "_section_mood");
        instrumentCode = persistCode(CodeType.INSTRUMENT, MARK + "_instrument");
        em.flush();
    }

    @Test
    @DisplayName("카탈로그: 장르·무드·보컬·악기·순간 무드가 external_id 에 묶여 함께 온다")
    void everyRecommendationAxisIsCarried() {
        Music music = persistMusic("축 검증 곡", false);
        em.persist(MusicAnalysis.builder()
            .musicId(music.getMusicId())
            .music(music)
            .hasVocal(true)
            .vocalGender(VocalGender.F)
            .build());
        em.persist(new MusicGenre(new MusicGenre.MusicGenreId(), music, genreCode));
        em.persist(new MusicMood(new MusicMood.MusicMoodId(), music, moodCode));
        MusicSectionAnalysis section = persistSection(music, 0, SectionLabel.CHORUS, 1000, 9000);
        em.persist(new MusicAnalysisInstrument(
            new MusicAnalysisInstrument.MusicAnalysisInstrumentId(), section, instrumentCode));
        em.persist(new MusicSectionAnalysisMood(
            new MusicSectionAnalysisMood.MusicSectionAnalysisMoodId(), section, sectionMoodCode));
        em.flush();
        em.clear();

        AiCatalogTrackResponse track = findTrack(music.getMusicId());
        assertNotNull(track, "카탈로그에 곡이 없다");
        assertEquals("축 검증 곡", track.title());
        assertEquals(Boolean.TRUE, track.hasVocal());
        assertEquals("F", track.vocalGender(), "보컬 성별이 서버 enum 이름으로 오지 않는다");
        assertEquals(List.of(genreCode.getCodeName()), track.genres(),
            "장르가 code_name 으로 오지 않는다 — 이 축이 비면 장르 조건이 통째로 사라진다");
        assertEquals(List.of(moodCode.getCodeName()), track.moods());

        assertEquals(1, track.sections().size());
        AiCatalogSectionResponse got = track.sections().get(0);
        assertEquals(section.getExternalId(), got.externalId(),
            "external_id 가 없으면 색인과 카탈로그를 이을 열쇠가 사라진다");
        assertEquals(section.getMusicSectionAnalysisId(), got.musicSectionAnalysisId());
        assertEquals("CHORUS", got.sectionLabel());
        assertEquals(1000, got.startTimeMs());
        assertEquals(9000, got.endTimeMs());
        assertEquals(List.of(instrumentCode.getCodeName()), got.instruments());
        assertEquals(List.of(sectionMoodCode.getCodeName()), got.moods(),
            "순간 무드가 곡 무드와 섞이거나 빠졌다 — 두 축은 코드 대역이 다르다");
    }

    @Test
    @DisplayName("카탈로그: 분석 행이 없는 곡은 보컬이 null 이고, 그래도 빠지지 않는다")
    void unanalyzedTrackSurvivesWithUnknownVocal() {
        Music music = persistMusic("미분석 곡", false);
        persistSection(music, 0, SectionLabel.VERSE, 0, 5000);
        em.flush();
        em.clear();

        AiCatalogTrackResponse track = findTrack(music.getMusicId());
        assertNotNull(track, "보컬 분석이 없다고 곡이 통째로 사라졌다");
        assertNull(track.hasVocal(), "모른다가 연주곡으로 바뀌었다");
        assertNull(track.vocalGender());
    }

    @Test
    @DisplayName("카탈로그: 삭제된 곡과 구간이 없는 곡은 실리지 않는다")
    void deletedAndSectionlessTracksAreExcluded() {
        Music deleted = persistMusic("삭제된 곡", true);
        persistSection(deleted, 0, SectionLabel.INTRO, 0, 4000);
        Music sectionless = persistMusic("구간 없는 곡", false);
        em.flush();
        em.clear();

        assertNull(findTrack(deleted.getMusicId()),
            "삭제된 곡이 추천 후보로 나갔다 — 이 API 의 본래 목적이 그것을 막는 것이다");
        assertNull(findTrack(sectionless.getMusicId()),
            "구간이 없는 곡은 추천될 수 없는데 실려 나갔다");
    }

    @Test
    @DisplayName("카탈로그: 페이지를 나눠도 곡이 겹치거나 사라지지 않는다")
    void paginationCoversEveryTrackExactlyOnce() {
        for (int i = 0; i < 7; i++) {
            Music music = persistMusic("쪽나눔 곡 " + i, false);
            persistSection(music, 0, SectionLabel.VERSE, 0, 5000);
            persistSection(music, 1, SectionLabel.CHORUS, 5000, 12000);
        }
        em.flush();
        em.clear();

        AiCatalogPageResponse first = aiCatalogService.getCatalogPage(0, 3);
        assertTrue(first.totalElements() >= 7);

        Set<Integer> seen = new HashSet<>();
        List<Integer> duplicated = new ArrayList<>();
        for (int page = 0; page < first.totalPages(); page++) {
            for (AiCatalogTrackResponse t : aiCatalogService.getCatalogPage(page, 3).tracks()) {
                if (!seen.add(t.musicId())) {
                    duplicated.add(t.musicId());
                }
                // 한 곡의 구간은 언제나 통째로 온다. 페이지 경계에 걸려 반쪽만 오면
                // 받는 쪽이 그것을 정상과 구분할 수 없다.
                assertFalse(t.sections().isEmpty(), "구간이 없는 곡이 실렸다: " + t.musicId());
            }
        }
        assertTrue(duplicated.isEmpty(), "곡이 두 페이지에 나왔다: " + duplicated);
        assertEquals(first.totalElements(), seen.size(),
            "페이지를 다 돌았는데 곡 수가 totalElements 와 다르다 — 카탈로그가 조용히 잘렸다");
    }

    @Test
    @DisplayName("카탈로그: 상한을 넘는 size 는 조용히 깎지 않고 거절한다")
    void oversizedPageIsRejectedNotTrimmed() {
        assertThrows(BusinessException.class,
            () -> aiCatalogController.getCatalog(0, 5000),
            "size 를 조용히 깎으면 부르는 쪽이 절반만 받고도 전부인 줄 안다");
        assertThrows(BusinessException.class, () -> aiCatalogController.getCatalog(-1, 10));
        assertThrows(BusinessException.class, () -> aiCatalogController.getCatalog(0, 0));
    }

    // ------------------------------------------------------------------ 픽스처

    /** 카탈로그 전체를 돌며 곡을 찾는다. 없으면 null — 빠졌는지도 확인해야 하기 때문이다. */
    private AiCatalogTrackResponse findTrack(int musicId) {
        AiCatalogPageResponse page = aiCatalogService.getCatalogPage(0, SCAN_SIZE);
        for (int n = 0; n < page.totalPages(); n++) {
            AiCatalogPageResponse current = n == 0 ? page
                : aiCatalogService.getCatalogPage(n, SCAN_SIZE);
            for (AiCatalogTrackResponse t : current.tracks()) {
                if (t.musicId() == musicId) {
                    return t;
                }
            }
        }
        return null;
    }

    private Music persistMusic(String title, boolean deleted) {
        Music music = Music.builder()
            .album(album)
            .artist(album.getArtist())
            .title(title)
            .releaseDate(LocalDateTime.now())
            .isDeleted(deleted)
            .build();
        em.persist(music);
        em.flush();
        return music;
    }

    private MusicSectionAnalysis persistSection(
        Music music, int index, SectionLabel label, int startMs, int endMs
    ) {
        MusicSectionAnalysis section = MusicSectionAnalysis.builder()
            .music(music)
            .sectionLabel(label)
            .index(index)
            .startTimeMs(startMs)
            .endTimeMs(endMs)
            .externalId("AITEST%d#%d".formatted(music.getMusicId(), index))
            .build();
        em.persist(section);
        em.flush();
        return section;
    }

    private Code persistCode(CodeType type, String name) {
        Code code = Code.builder()
            .codeType(type)
            .codeName(name)
            .displayName(name)
            .build();
        em.persist(code);
        return code;
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
        return file;
    }

}
