package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.error.MusicSectionErrorCode;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;
import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;
import com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicSectionAnalysisMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.response.MusicSectionBulkResponse;
import com.osundosun.mupid.api.music.response.MusicSectionRef;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 구간 일괄 적재(`PUT /api/v1/music/{musicId}/section`)의 서비스 계층 검증.
 *
 * 이 표가 채워져야 숫자 `music_section_id` 가 생기고, 그래야 추천 서버의
 * `/internal/ready` 가 200 이 된다. 비어 있는 동안 곡 추천은 0건이다.
 *
 * 로컬 MySQL 의 실제 스키마를 그대로 쓴다 — `external_id` 의 `VARCHAR(64) UNIQUE`
 * 는 인메모리 DB 로는 검증되지 않는다. 트랜잭션은 테스트마다 롤백된다.
 *
 * 애너테이션 검증(`@NotEmpty`·`@Size`)은 컨트롤러 계층에서 걸리므로 여기서는
 * 서비스가 직접 막는 것만 본다.
 */
@SpringBootTest
@Transactional
class MusicSectionReplaceTest {

    private static final String VERSION = "v1";

    @Autowired
    private MusicSectionService sectionService;

    @Autowired
    private MusicSectionAnalysisRepository sectionRepository;

    @Autowired
    private MusicSectionAnalysisMoodRepository moodRepository;

    @Autowired
    private MusicAnalysisInstrumentRepository instrumentRepository;

    @Autowired
    private CodeRepository codeRepository;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private Music otherMusic;

    /** 테스트끼리 `external_id` 가 겹치지 않게 한다 — 컬럼이 전역 UNIQUE 다. */
    private String uid;

    @BeforeEach
    void setUp() {
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
        otherMusic = persistMusic(album, artist, "새벽 드라이브");
        uid = "T" + UUID.randomUUID().toString().substring(0, 8);
        em.flush();
    }

    @Test
    @DisplayName("빈 곡에 구간을 넣으면 전부 들어가고 발급된 번호를 돌려준다")
    void writesAllSectionsAndReturnsIssuedNumbers() {
        MusicSectionBulkResponse result = replace(music,
            section(0, SectionLabel.INTRO, 0, 15_500),
            section(1, SectionLabel.CHORUS, 15_500, 45_000));

        assertEquals(music.getMusicId(), result.musicId());
        assertEquals(VERSION, result.analysisVersion());
        assertEquals(0, result.replaced());
        assertEquals(2, result.sections().size());

        /* AI 가 id_map 을 만드는 근거다. 번호가 비어 있으면 매핑을 못 만든다 */
        for (MusicSectionRef ref : result.sections()) {
            assertTrue(ref.musicSectionAnalysisId() > 0,
                "채번되지 않은 번호를 돌려줬다: " + ref);
        }
        assertEquals(List.of(uid + "#0", uid + "#1"),
            result.sections().stream().map(MusicSectionRef::externalId).toList());
        assertEquals(List.of(SectionLabel.INTRO, SectionLabel.CHORUS), labels(music));
    }

    @Test
    @DisplayName("다시 넣으면 기존 구간을 지우고 갈아 끼운다 — 두 벌이 겹치지 않는다")
    void replacesInsteadOfAppending() {
        replace(music,
            section(0, SectionLabel.INTRO, 0, 10_000),
            section(1, SectionLabel.VERSE, 10_000, 20_000),
            section(2, SectionLabel.OUTRO, 20_000, 30_000));

        MusicSectionBulkResponse result = replace(music,
            section(0, SectionLabel.CHORUS, 0, 30_000));

        assertEquals(3, result.replaced());
        assertEquals(1, result.sections().size());
        assertEquals(List.of(SectionLabel.CHORUS), labels(music));
    }

    @Test
    @DisplayName("같은 본문을 두 번 보내면 구간 구성이 같다")
    void isIdempotent() {
        MusicSectionBulkLine[] sections = {
            section(0, SectionLabel.INTRO, 0, 15_500),
            section(1, SectionLabel.CHORUS, 15_500, 45_000)};

        replace(music, sections);
        List<SectionLabel> first = labels(music);
        replace(music, sections);

        assertEquals(first, labels(music));
        assertEquals(2, labels(music).size());
    }

    @Test
    @DisplayName("다른 곡의 구간은 건드리지 않는다")
    void leavesOtherMusicAlone() {
        sectionService.replaceSections(otherMusic.getMusicId(), request(
            new MusicSectionBulkLine(0, uid + "-other#0", SectionLabel.VERSE, 0, 9_000, null, null)));
        em.flush();

        replace(music, section(0, SectionLabel.INTRO, 0, 15_500));

        assertEquals(List.of(SectionLabel.VERSE), labels(otherMusic));
    }

    @Test
    @DisplayName("종료가 시작보다 앞서거나 같으면 400 이고 하나도 넣지 않는다")
    void rejectsInvertedTimeRange() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> replace(music,
                section(0, SectionLabel.INTRO, 0, 15_500),
                section(1, SectionLabel.CHORUS, 45_000, 45_000)));

        assertEquals(MusicSectionErrorCode.SECTION_INVALID_TIME_RANGE, e.getErrorCode());
        assertTrue(labels(music).isEmpty(), "거절했는데 일부가 들어갔다");
    }

    @Test
    @DisplayName("구간 순번이 겹치면 400 이다")
    void rejectsDuplicateIndex() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> replace(music,
                section(0, SectionLabel.INTRO, 0, 10_000),
                section(0, SectionLabel.CHORUS, 10_000, 20_000)));

        assertEquals(MusicSectionErrorCode.SECTION_INDEX_DUPLICATED, e.getErrorCode());
    }

    @Test
    @DisplayName("한 요청 안에서 external_id 가 겹치면 409 다")
    void rejectsDuplicateExternalIdWithinRequest() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> replace(music,
                new MusicSectionBulkLine(0, uid + "#0", SectionLabel.INTRO, 0, 10_000, null, null),
                new MusicSectionBulkLine(1, uid + "#0", SectionLabel.CHORUS, 10_000, 20_000, null, null)));

        assertEquals(MusicSectionErrorCode.SECTION_EXTERNAL_ID_DUPLICATED, e.getErrorCode());
    }

    @Test
    @DisplayName("다른 곡이 이미 쓰고 있는 external_id 는 409 다")
    void rejectsExternalIdTakenByAnotherMusic() {
        replace(music, section(0, SectionLabel.INTRO, 0, 15_500));

        BusinessException e = assertThrows(BusinessException.class,
            () -> sectionService.replaceSections(otherMusic.getMusicId(), request(
                section(0, SectionLabel.VERSE, 0, 9_000))));

        assertEquals(MusicSectionErrorCode.SECTION_EXTERNAL_ID_DUPLICATED, e.getErrorCode());
        /* 거절당한 곡은 여전히 비어 있어야 한다 */
        assertTrue(labels(otherMusic).isEmpty());
    }

    @Test
    @DisplayName("같은 곡을 다시 적재할 때는 external_id 가 그대로여도 통과한다")
    void allowsReusingOwnExternalIds() {
        MusicSectionBulkLine[] sections = {section(0, SectionLabel.INTRO, 0, 15_500)};
        replace(music, sections);

        MusicSectionBulkResponse result = replace(music, sections);

        assertEquals(1, result.replaced());
        assertEquals(1, result.sections().size());
    }

    @Test
    @DisplayName("상한을 넘기면 하나도 넣지 않고 이미 있던 구간은 남는다")
    void rejectsTooManySections() {
        replace(music, section(0, SectionLabel.INTRO, 0, 15_500));

        List<MusicSectionBulkLine> tooMany = new ArrayList<>();
        for (int i = 0; i <= MusicSectionBulkRequest.MAX_SECTIONS; i++) {
            tooMany.add(new MusicSectionBulkLine(
                i, uid + "-many#" + i, SectionLabel.VERSE, i * 1_000, i * 1_000 + 500, null, null));
        }

        BusinessException e = assertThrows(BusinessException.class,
            () -> sectionService.replaceSections(music.getMusicId(),
                new MusicSectionBulkRequest(VERSION, tooMany)));

        assertEquals(MusicSectionErrorCode.SECTION_BULK_TOO_MANY, e.getErrorCode());
        assertEquals(List.of(SectionLabel.INTRO), labels(music));
    }

    @Test
    @DisplayName("없는 곡이면 404 다")
    void rejectsMissingMusic() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> sectionService.replaceSections(-1, request(
                section(0, SectionLabel.INTRO, 0, 15_500))));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제된 곡에는 구간을 넣을 수 없다 — 404 가 아니라 410 이다")
    void rejectsDeletedMusic() {
        music.setIsDeleted(true);
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> replace(music, section(0, SectionLabel.INTRO, 0, 15_500)));

        /* 행이 남으므로 "없는 곡" 과 "지워진 곡" 을 가른다 (2026-09-18 변경). */
        assertEquals(MusicErrorCode.MUSIC_GONE, e.getErrorCode());
    }

    @Test
    @DisplayName("external_id 앞뒤 공백은 잘라서 저장한다")
    void trimsExternalId() {
        replace(music, new MusicSectionBulkLine(
            0, "  " + uid + "#0  ", SectionLabel.INTRO, 0, 15_500, null, null));

        MusicSectionAnalysis row =
            sectionRepository.findByMusic_MusicIdOrderByIndexAsc(music.getMusicId()).get(0);
        assertEquals(uid + "#0", row.getExternalId());
    }

    @Test
    @DisplayName("응답의 번호로 실제 행을 찾을 수 있다 — id_map 의 근거다")
    void issuedNumbersPointAtRealRows() {
        MusicSectionBulkResponse result = replace(music,
            section(0, SectionLabel.INTRO, 0, 15_500),
            section(1, SectionLabel.CHORUS, 15_500, 45_000));

        for (MusicSectionRef ref : result.sections()) {
            MusicSectionAnalysis row = sectionRepository
                .findById(ref.musicSectionAnalysisId())
                .orElseThrow(() -> new AssertionError("없는 번호: " + ref));
            assertEquals(ref.externalId(), row.getExternalId());
            assertEquals(ref.sectionIndex(), row.getIndex());
            assertEquals(music.getMusicId(), row.getMusic().getMusicId());
        }
        assertFalse(result.sections().isEmpty());
    }

    @Test
    @DisplayName("분위기를 이름으로 받아 코드 번호로 잇는다")
    void linksMoodsByName() {
        String mood = codeRepository.findAllByCodeTypeOrderByCodeIdAsc(CodeType.MOOD)
            .getFirst().getCodeName();

        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, List.of(mood), null));
        em.flush();

        List<MusicSectionAnalysisMood> links = moodRepository
            .findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
                List.of(result.sections().getFirst().musicSectionAnalysisId()));
        assertEquals(1, links.size());
        assertEquals(mood, links.getFirst().getMoodCode().getCodeName());
    }

    @Test
    @DisplayName("분위기는 화면용 한국어 이름으로도 잇는다 — 구간 등록 도구가 한국어를 보낸다")
    void linksMoodsByDisplayName() {
        Code code = codeRepository.findAllByCodeTypeOrderByCodeIdAsc(CodeType.MOOD).getFirst();
        assertNotEquals(code.getCodeName(), code.getDisplayName(),
            "코드명과 표시명이 같으면 이 검증이 의미가 없다");

        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, List.of(code.getDisplayName()), null));
        em.flush();

        List<MusicSectionAnalysisMood> links = moodRepository
            .findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
                List.of(result.sections().getFirst().musicSectionAnalysisId()));
        assertEquals(1, links.size());
        assertEquals(code.getCodeId(), links.getFirst().getMoodCode().getCodeId());
    }

    @Test
    @DisplayName("코드표에 없는 분위기는 버리고 구간은 살린다")
    void dropsUnknownMoodButKeepsTheSection() {
        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, List.of("없는분위기"), null));
        em.flush();

        assertEquals(1, result.sections().size(), "분위기 때문에 구간까지 잃으면 안 된다");
        assertTrue(moodRepository
            .findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(
                List.of(result.sections().getFirst().musicSectionAnalysisId()))
            .isEmpty());
    }

    @Test
    @DisplayName("구간을 갈아 끼우면 옛 분위기 연결도 함께 사라진다")
    void replacingSectionsClearsOldMoodLinks() {
        String mood = codeRepository.findAllByCodeTypeOrderByCodeIdAsc(CodeType.MOOD)
            .getFirst().getCodeName();
        MusicSectionBulkResponse first = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, List.of(mood), null));
        em.flush();
        int oldSectionId = first.sections().getFirst().musicSectionAnalysisId();

        replace(music, section(0, SectionLabel.CHORUS, 0, 15_500));
        em.flush();

        assertTrue(moodRepository
            .findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(List.of(oldSectionId))
            .isEmpty(), "지워진 구간의 분위기 연결이 남았다");
    }

    /*
     * 악기는 구간에 달린다. 그리고 `music_analysis_instruments` 는 이 변경 전까지
     * 0행이었다 — 관계와 `@EmbeddedId` 가 같은 컬럼을 각자 매핑하고 있어서 쓰는
     * 순간 터지는 상태였고, 쓴 적이 없어 드러나지 않았다. 그래서 여기서는 실제로
     * INSERT 가 되는지를 본다.
     */

    @Test
    @DisplayName("악기를 이름으로 받아 구간에 잇는다 — 이 표가 처음으로 쓰이는 경로다")
    void linksInstrumentsByName() {
        Code guitar = persistInstrument("guitar");

        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, null,
            List.of(guitar.getCodeName())));
        em.flush();

        List<MusicAnalysisInstrument> links = instrumentRepository
            .findByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(
                List.of(result.sections().getFirst().musicSectionAnalysisId()));
        assertEquals(1, links.size());
        assertEquals(guitar.getCodeName(), links.getFirst().getInstrumentCode().getCodeName());
    }

    @Test
    @DisplayName("구간마다 악기가 다르다 — 곡 단위로 뭉치지 않는다")
    void instrumentsAreKeptPerSection() {
        Code guitar = persistInstrument("guitar");
        Code drums = persistInstrument("drums");

        MusicSectionBulkResponse result = replace(music,
            new MusicSectionBulkLine(0, uid + "#0", SectionLabel.INTRO, 0, 10_000, null,
                List.of(guitar.getCodeName())),
            new MusicSectionBulkLine(1, uid + "#1", SectionLabel.CHORUS, 10_000, 20_000, null,
                List.of(guitar.getCodeName(), drums.getCodeName())));
        em.flush();

        assertEquals(List.of(guitar.getCodeName()),
            instrumentNames(result.sections().get(0).musicSectionAnalysisId()));
        assertEquals(List.of(drums.getCodeName(), guitar.getCodeName()),
            instrumentNames(result.sections().get(1).musicSectionAnalysisId()));
    }

    @Test
    @DisplayName("같은 악기를 두 번 주면 한 번만 저장한다 — 중복 키로 터지지 않는다")
    void deduplicatesRepeatedInstruments() {
        Code guitar = persistInstrument("guitar");

        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, null,
            List.of(guitar.getCodeName(), guitar.getCodeName())));
        em.flush();

        assertEquals(1, instrumentNames(
            result.sections().getFirst().musicSectionAnalysisId()).size());
    }

    @Test
    @DisplayName("코드표에 없는 악기는 버리고 구간은 살린다")
    void dropsUnknownInstrumentButKeepsTheSection() {
        MusicSectionBulkResponse result = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, null, List.of("없는악기")));
        em.flush();

        assertEquals(1, result.sections().size(), "악기 때문에 구간까지 잃으면 안 된다");
        assertTrue(instrumentNames(
            result.sections().getFirst().musicSectionAnalysisId()).isEmpty());
    }

    @Test
    @DisplayName("구간을 갈아 끼우면 옛 악기 연결도 함께 사라진다")
    void replacingSectionsClearsOldInstrumentLinks() {
        Code guitar = persistInstrument("guitar");
        MusicSectionBulkResponse first = replace(music, new MusicSectionBulkLine(
            0, uid + "#0", SectionLabel.INTRO, 0, 15_500, null,
            List.of(guitar.getCodeName())));
        em.flush();
        int oldSectionId = first.sections().getFirst().musicSectionAnalysisId();

        replace(music, section(0, SectionLabel.CHORUS, 0, 15_500));
        em.flush();

        assertTrue(instrumentNames(oldSectionId).isEmpty(),
            "지워진 구간의 악기 연결이 남았다");
    }

    // ─────────────────────────────────────────────────────────────

    /** 이름 순으로 정렬한 그 구간의 악기. 저장 순서에 기대지 않는다. */
    private List<String> instrumentNames(int sectionAnalysisId) {
        return instrumentRepository
            .findByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(List.of(sectionAnalysisId))
            .stream()
            .map(link -> link.getInstrumentCode().getCodeName())
            .sorted()
            .toList();
    }

    /**
     * `CodeType.INSTRUMENT` 는 부트스트랩이 심지 않는다 — 확정된 목록이 없어서다.
     * 그래서 테스트가 직접 넣는다. 이름은 테스트끼리 겹치지 않게 만든다.
     */
    private Code persistInstrument(String name) {
        Code code = Code.builder()
            .codeType(CodeType.INSTRUMENT)
            .codeName(name + "-" + uid)
            /* `codes.display_name` 은 NOT NULL 이다. 표시명을 비우면 INSERT 가
               터진다 — `CodeOption.of` 가 대신 채워 주지만 그건 읽을 때다. */
            .displayName(name + "-" + uid)
            .build();
        em.persist(code);
        em.flush();
        return code;
    }

    private MusicSectionBulkResponse replace(Music target, MusicSectionBulkLine... sections) {
        MusicSectionBulkResponse result =
            sectionService.replaceSections(target.getMusicId(), request(sections));
        /* 저장이 실제로 나갔는지 보려면 밀어낸다 */
        em.flush();
        return result;
    }

    private static MusicSectionBulkRequest request(MusicSectionBulkLine... sections) {
        return new MusicSectionBulkRequest(VERSION, List.of(sections));
    }

    private MusicSectionBulkLine section(int index, SectionLabel label, int startMs, int endMs) {
        return new MusicSectionBulkLine(index, uid + "#" + index, label, startMs, endMs, null, null);
    }

    /** 조회 순서(`index`) 그대로의 구간 종류. */
    private List<SectionLabel> labels(Music target) {
        em.flush();
        em.clear();
        return sectionRepository.findByMusic_MusicIdOrderByIndexAsc(target.getMusicId())
            .stream()
            .map(MusicSectionAnalysis::getSectionLabel)
            .toList();
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
