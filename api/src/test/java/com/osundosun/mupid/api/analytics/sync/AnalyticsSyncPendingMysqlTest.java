package com.osundosun.mupid.api.analytics.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.analytics.sync.dto.SectionDigest;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.repository.SectionDigestRepository;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository.SectionInstrument;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 미반영 기록과 MySQL 쪽 요약을 <b>실제 MySQL</b> 로 확인한다 (S15P21A204-451).
 *
 * <p>문장이 전부 MySQL 8 네이티브({@code INSERT … AS new ON DUPLICATE KEY UPDATE},
 * {@code FOR UPDATE SKIP LOCKED}, {@code CRC32}) 라 목으로는 문법조차 확인되지 않는다.
 * 로컬 dev MySQL 에 {@code migrate-analytics-sync-2026-09-25.sql} 이 적용돼 있어야 한다.
 * 트랜잭션은 시험마다 롤백되므로 커밋 뒤 ClickHouse 쓰기는 돌지 않는다 —
 * 그래서 구간 교체 뒤 <b>미반영 행이 남아 있는 것</b>이 정상이다. 구간 교체는 곡 특징
 * ({@code TRACK_FEATURES}, S15P21A204-455)도 함께 올리므로 행을 셀 때는 종류를 건다.
 */
@SpringBootTest
@Transactional
class AnalyticsSyncPendingMysqlTest {

    private static final String KIND = "CLICKHOUSE";

    @Autowired
    private MusicSectionService sectionService;

    @Autowired
    private MusicSectionAnalysisRepository sectionRepository;

    @Autowired
    private AnalyticsSyncPendingRepository pendingRepository;

    @Autowired
    private SectionDigestRepository digestRepository;

    @Autowired
    private MusicAnalysisInstrumentRepository instrumentRepository;

    @Autowired
    private AdminResourceService adminResourceService;

    @Autowired
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysql;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private String uid;

    @BeforeEach
    void setUp() {
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.ALBUM)
            .originalFileName("cover.jpg")
            .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
            .fileSizeBytes(1024)
            .mime("image/jpeg")
            .build();
        em.persist(image);
        Artist artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);
        Album album = Album.builder().artist(artist).title("Blue Static").imageFile(image)
            .releaseDate(LocalDateTime.now()).build();
        em.persist(album);
        music = Music.builder().album(album).artist(artist).title("푸른 오후")
            .releaseDate(LocalDateTime.now()).build();
        em.persist(music);
        uid = "T" + UUID.randomUUID().toString().substring(0, 8);
        em.flush();
    }

    private void replace(String version, int sections) {
        replace(version, sections, null);
    }

    /** @param firstInstruments 첫 구간에만 다는 악기 이름. 나머지 구간은 악기가 없다 */
    private void replace(String version, int sections, List<String> firstInstruments) {
        List<MusicSectionBulkLine> lines = java.util.stream.IntStream.range(0, sections)
            .mapToObj(i -> new MusicSectionBulkLine(i, uid + "#" + i, SectionLabel.CHORUS,
                i * 10_000, (i + 1) * 10_000, null, i == 0 ? firstInstruments : null,
                i == 0 ? 0.7071067811865476 : null))
            .toList();
        sectionService.replaceSections(music.getMusicId(), new MusicSectionBulkRequest(version, lines));
        em.flush();
    }

    @Test
    @DisplayName("구간 교체는 같은 트랜잭션에 미반영 행을 남기고, 버전을 MySQL 구간에도 쓴다")
    void replaceRecordsPendingAndStoresVersion() {
        replace("v1", 2);

        assertThat(pendingRepository.findRevision(music.getMusicId(), KIND)).contains(1L);
        assertThat(mysql.queryForObject(
            "SELECT analysis_version FROM analytics_sync_pending WHERE music_id = ? AND kind = ?",
            String.class, music.getMusicId(), KIND)).isEqualTo("v1");
        em.clear();
        assertThat(sectionRepository.findByMusic_MusicIdOrderByIndexAsc(music.getMusicId()))
            .extracting(MusicSectionAnalysis::getAnalysisVersion)
            .containsOnly("v1");
    }

    @Test
    @DisplayName("같은 곡이 다시 오면 한 행을 갱신한다 — revision 이 오르고 처음 시각은 그대로다")
    void sameMusicUpdatesOneRow() {
        replace("v1", 2);
        mysql.update("UPDATE analytics_sync_pending SET created_at = created_at - INTERVAL 1 HOUR "
            + "WHERE music_id = ? AND kind = 'CLICKHOUSE'", music.getMusicId());
        replace("v2", 3);

        assertThat(pendingRepository.findRevision(music.getMusicId(), KIND)).contains(2L);
        assertThat(mysql.queryForObject(
            "SELECT COUNT(*) FROM analytics_sync_pending WHERE music_id = ? AND kind = 'CLICKHOUSE'", Integer.class,
            music.getMusicId())).isEqualTo(1);
        assertThat(mysql.queryForObject(
            "SELECT analysis_version FROM analytics_sync_pending WHERE music_id = ? AND kind = 'CLICKHOUSE'", String.class,
            music.getMusicId())).isEqualTo("v2");
        // 다시 기록돼도 "처음 미반영이 된 시각" 은 그대로여야 밀린 나이가 보인다.
        assertThat(pendingRepository.oldestAgeSeconds(KIND)).isGreaterThanOrEqualTo(3_600L);
    }

    @Test
    @DisplayName("앞선 revision 으로는 지우지 못한다 — 뒤 요청의 미반영을 지키는 규칙")
    void deleteRequiresOwnRevision() {
        replace("v1", 1);
        replace("v1", 1);

        assertThat(pendingRepository.deleteIfRevision(music.getMusicId(), KIND, 1L)).isZero();
        assertThat(pendingRepository.deleteIfRevision(music.getMusicId(), KIND, 2L)).isEqualTo(1);
        assertThat(pendingRepository.findRevision(music.getMusicId(), KIND)).isEmpty();
    }

    @Test
    @DisplayName("재시도 문장들 — 가져가기(SKIP LOCKED)·실패 적기·후보·집계")
    void retryStatements() {
        replace("v1", 1);
        int musicId = music.getMusicId();

        assertThat(pendingRepository.findCandidates(KIND, 100_000)).contains(musicId);
        assertThat(pendingRepository.claim(musicId, KIND)).contains(1L);
        assertThat(pendingRepository.markFailed(musicId, KIND, "ConnectException: refused")).isEqualTo(1);
        assertThat(mysql.queryForObject(
            "SELECT attempts FROM analytics_sync_pending WHERE music_id = ? AND kind = 'CLICKHOUSE'", Integer.class, musicId))
            .isEqualTo(1);
        assertThat(pendingRepository.countPending(KIND)).isPositive();
        assertThat(pendingRepository.oldestAgeSeconds(KIND)).isNotNull();
        assertThat(pendingRepository.deletePending(musicId, KIND)).isEqualTo(1);
    }

    @Test
    @DisplayName("MySQL 요약의 체크섬은 행 문자열 CRC32 의 합이다 — ClickHouse 문장과 같은 규칙 (에너지·악기 포함)")
    void digestMatchesDocumentedFormula() {
        // 코드 번호가 먼저인 쪽이 앞에 온다. 요청에는 거꾸로 싣는다 — 순서는 요청이 아니라 코드표가 정한다.
        String first = instrument("keys");
        String second = instrument("other");
        replace("v1", 2, List.of(second, first));
        em.clear();

        long expected = 0;
        for (MusicSectionAnalysis row : sectionRepository.findByMusic_MusicIdOrderByIndexAsc(music.getMusicId())) {
            CRC32 crc = new CRC32();
            crc.update(String.join("|",
                String.valueOf(row.getMusicSectionAnalysisId()), String.valueOf(row.getIndex()),
                row.getExternalId(), row.getSectionLabel().name(),
                String.valueOf(row.getStartTimeMs()), String.valueOf(row.getEndTimeMs()),
                // 에너지는 백만 배 내림 정수, 없으면 -1 (S15P21A204-455)
                String.valueOf(row.getEnergy() == null ? -1 : (long)Math.floor(row.getEnergy() * 1_000_000)),
                // 악기는 코드 번호순 ',' 로, 없으면 빈 문자열 (S15P21A204-458)
                row.getIndex() == 0 ? first + "," + second : "")
                .getBytes(StandardCharsets.UTF_8));
            expected += crc.getValue();
        }

        Map<TrackVersion, SectionDigest> digests = digestRepository.digests();
        assertThat(digests.get(new TrackVersion(music.getMusicId(), "v1")))
            .isEqualTo(new SectionDigest(2, expected));
    }

    @Test
    @DisplayName("사본에 싣는 악기는 코드 이름 그대로, 코드 번호순이고 other 도 남는다")
    void instrumentsForTheCopyAreRawCodeNamesInCodeOrder() {
        String first = instrument("keys");
        String second = instrument("other");
        replace("v1", 2, List.of(second, first));

        List<Integer> ids = sectionRepository.findByMusic_MusicIdOrderByIndexAsc(music.getMusicId()).stream()
            .map(MusicSectionAnalysis::getMusicSectionAnalysisId).toList();

        // `TrackSectionSync` 가 ClickHouse `track_sections.instruments` 로 옮기는 값이다. 구체적이지
        // 않은 악기를 조건에서 빼는 것은 추천 서버 몫이라 여기서는 거르지 않는다.
        assertThat(instrumentRepository.findCodeNamesBySectionIds(ids)).containsExactly(
            new SectionInstrument(ids.get(0), first),
            new SectionInstrument(ids.get(0), second));
    }

    @Test
    @DisplayName("관리자 화면에서 구간 악기 연결이나 악기 코드를 고치면 그 곡의 구간 미반영 행이 남는다")
    void adminInstrumentEditsRecordPending() {
        String name = instrument("keys");
        replace("v1", 1, List.of(name));
        int sectionId = sectionRepository.findByMusic_MusicIdOrderByIndexAsc(music.getMusicId())
            .getFirst().getMusicSectionAnalysisId();
        int codeId = mysql.queryForObject("SELECT code_id FROM codes WHERE code_name = ?", Integer.class, name);

        // 악기 코드 이름은 사본(`track_sections.instruments`)에 그대로 들어간다 — 그 악기를 단 곡 전부가 바뀐다.
        pendingRepository.deletePending(music.getMusicId(), KIND);
        adminResourceService.update("code", String.valueOf(codeId), Map.of());
        assertThat(pendingRepository.findRevision(music.getMusicId(), KIND)).as("악기 코드").isPresent();

        // 연결 한 줄을 지워도 그 구간의 악기가 바뀐다. 복합키는 "구간번호,코드번호" 다.
        pendingRepository.deletePending(music.getMusicId(), KIND);
        adminResourceService.delete("musicAnalysisInstrument", sectionId + "," + codeId);
        assertThat(pendingRepository.findRevision(music.getMusicId(), KIND)).as("악기 연결").isPresent();
    }

    /** 악기 코드. 부트스트랩이 심지 않는 종류라 시험이 만든다 — 이름은 겹치지 않게 붙인다. */
    private String instrument(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
        em.persist(Code.builder().codeType(CodeType.INSTRUMENT).codeName(name).displayName(name).build());
        em.flush();
        return name;
    }

}
