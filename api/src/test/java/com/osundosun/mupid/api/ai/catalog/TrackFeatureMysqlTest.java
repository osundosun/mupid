package com.osundosun.mupid.api.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;
import com.osundosun.mupid.api.analytics.sync.repository.TrackFeatureSource;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.enumerate.VocalGender;
import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.request.MusicClassificationRequest;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.service.MusicAnalysisService;
import com.osundosun.mupid.api.music.service.MusicClassificationService;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.stream.enumerate.Codec;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.request.StreamCreateRequest;
import com.osundosun.mupid.api.stream.service.StreamService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 곡 특징 사본의 <b>쓰기 입구</b>와 <b>값</b>을 실제 MySQL 로 확인한다 (S15P21A204-455).
 *
 * <ul>
 *   <li>입구 — 분석 값·장르/무드·곡 삭제·구간 교체·스트림 생성/상태/삭제·관리자 화면(복구, 코드
 *       이름)이 모두 같은 트랜잭션에 {@code TRACK_FEATURES} 미반영 행을 남긴다.</li>
 *   <li>값 — {@link TrackFeatureSource} 가 만든 곡 특징이 추천 본문
 *       ({@link RecommenderCatalogServiceImpl#build})의 곡 줄과 같다. 서빙이 본문 대신 ClickHouse
 *       사본을 읽어도 순위가 바뀌지 않는다는 근거다(AI 쪽은 {@code test_feature_store.py}). 계약 v3
 *       (S15P21A204-458)에서 본문은 없어졌지만 {@code build()} 는 조건 어휘의 후보로 남아, 같은 규칙의
 *       기준으로 계속 쓴다.</li>
 * </ul>
 *
 * <p>시험마다 롤백되므로 커밋 뒤 ClickHouse 쓰기는 돌지 않는다 — 미반영 행이 <b>남아 있는 것</b>이
 * 정상이다. 본문 비교를 위해 {@code ai.catalog} 패키지에 둔다({@code build()} 가 패키지 범위다).
 */
@SpringBootTest
@Transactional
class TrackFeatureMysqlTest {

    private static final String KIND = "TRACK_FEATURES";

    @Autowired
    private MusicSectionService sectionService;
    @Autowired
    private MusicAnalysisService analysisService;
    @Autowired
    private MusicClassificationService classificationService;
    @Autowired
    private MusicService musicService;
    @Autowired
    private StreamService streamService;
    @Autowired
    private AdminResourceService adminResourceService;
    @Autowired
    private TrackFeatureSource source;
    @Autowired
    private RecommenderCatalogServiceImpl catalogService;
    @Autowired
    @Qualifier("jdbcTemplate")
    private JdbcTemplate mysql;
    @PersistenceContext
    private EntityManager em;

    private Music music;
    private String track;

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
        track = "T" + UUID.randomUUID().toString().substring(0, 8);
        em.flush();
        clearPending();
    }

    private int id() {
        return music.getMusicId();
    }

    private void clearPending() {
        mysql.update("DELETE FROM analytics_sync_pending WHERE music_id = ?", music.getMusicId());
    }

    private boolean pending() {
        return mysql.queryForObject(
            "SELECT COUNT(*) FROM analytics_sync_pending WHERE music_id = ? AND kind = ?",
            Integer.class, id(), KIND) == 1;
    }

    private void sections(int count) {
        List<MusicSectionBulkLine> lines = IntStream.range(0, count)
            .mapToObj(i -> new MusicSectionBulkLine(i, track + "#" + i, SectionLabel.CHORUS,
                i * 10_000, (i + 1) * 10_000, null, null, i == 0 ? null : 0.25 * i))
            .toList();
        sectionService.replaceSections(id(), new MusicSectionBulkRequest("v1", lines));
        em.flush();
    }

    private int stream(int durationMs) {
        int streamId = streamService.createStream(id(),
            new StreamCreateRequest(128, durationMs % 2 == 0 ? Codec.OPUS : Codec.AAC, durationMs, 0));
        em.flush();
        return streamId;
    }

    /** 장르·무드 코드 번호 둘씩. dev DB 에 있는 것을 쓴다. */
    private List<Integer> codes(String type) {
        return mysql.queryForList(
            "SELECT code_id FROM codes WHERE code_type = ? ORDER BY code_id DESC LIMIT 2",
            Integer.class, type);
    }

    @Test
    @DisplayName("값이 바뀌는 입구마다 같은 트랜잭션에 곡 특징 미반영 행이 남는다")
    void everyEntranceRecordsPending() {
        sections(2);
        assertThat(pending()).as("구간 교체").isTrue();

        clearPending();
        analysisService.replaceAnalysis(id(),
            new MusicAnalysisRequest(true, VocalGender.F, 120.5, null, null, "ko", "ballad"));
        em.flush();
        assertThat(pending()).as("분석 값").isTrue();

        clearPending();
        classificationService.replaceClassification(id(),
            new MusicClassificationRequest(codes("GENRE"), codes("MOOD")));
        em.flush();
        assertThat(pending()).as("장르·무드").isTrue();

        clearPending();
        int streamId = stream(180_000);
        assertThat(pending()).as("스트림 생성").isTrue();

        clearPending();
        streamService.updateStatus(streamId, StreamStatus.DONE);
        em.flush();
        assertThat(pending()).as("스트림 상태").isTrue();

        clearPending();
        streamService.deleteStream(streamId);
        em.flush();
        assertThat(pending()).as("스트림 삭제").isTrue();

        clearPending();
        musicService.deleteMusic(id());
        em.flush();
        assertThat(pending()).as("곡 삭제").isTrue();

        clearPending();
        adminResourceService.update("music", String.valueOf(id()), Map.of("isDeleted", false));
        assertThat(pending()).as("관리자 화면 곡 복구").isTrue();

        clearPending();
        int genre = codes("GENRE").get(0);
        adminResourceService.update("code", String.valueOf(genre), Map.of());
        assertThat(pending()).as("관리자 화면 장르 코드 — 그 코드를 단 곡").isTrue();
    }

    @Test
    @DisplayName("곡 특징은 추천 본문의 곡 줄과 같은 값이다 — 삭제·재생 가능은 따로 들고 있다")
    void featuresMatchCatalogBody() {
        sections(3);
        analysisService.replaceAnalysis(id(),
            new MusicAnalysisRequest(true, VocalGender.MIX, 98.765, 90, 110, "en", "pop"));
        classificationService.replaceClassification(id(),
            new MusicClassificationRequest(codes("GENRE"), codes("MOOD")));
        int shorter = stream(200_000);
        int longer = stream(200_001);
        streamService.updateStatus(shorter, StreamStatus.DONE);
        streamService.updateStatus(longer, StreamStatus.DONE);
        em.flush();

        TrackFeatureRow row = source.load(id());
        RecommenderCatalog.Track expected = catalogService.build().tracks().stream()
            .filter(t -> t.musicId() == id()).findFirst().orElseThrow();

        assertThat(row.track()).isEqualTo(expected.track()).isEqualTo(track);
        assertThat(row.durationMs()).isEqualTo(expected.durationMs()).isEqualTo(200_001);
        assertThat(row.bpm()).isEqualTo(expected.bpm());
        assertThat(row.bpmRequestedMin()).isEqualTo(expected.bpmRequestedMin());
        assertThat(row.bpmRequestedMax()).isEqualTo(expected.bpmRequestedMax());
        assertThat(row.language()).isEqualTo(expected.language());
        assertThat(row.category()).isEqualTo(expected.category());
        assertThat(row.genres()).isEqualTo(expected.genres()).hasSize(2);
        assertThat(row.moods()).isEqualTo(expected.moods()).hasSize(2);
        assertThat(row.hasVocal()).isEqualTo(expected.hasVocal());
        assertThat(row.vocalGender()).isEqualTo(expected.vocalGender());
        assertThat(row.isDeleted()).isFalse();
        assertThat(row.playable()).isTrue();
        assertThat(row.analysisVersion()).isEqualTo("v1");

        musicService.deleteMusic(id());
        em.flush();
        TrackFeatureRow deleted = source.load(id());
        assertThat(deleted.isDeleted()).isTrue();
        // 본문에서는 빠진다 — 사본은 값으로 들고 있다.
        assertThat(catalogService.build().tracks()).noneMatch(t -> t.musicId() == id());
    }

    @Test
    @DisplayName("스트림도 구간도 없는 곡 — 재생 불가, 곡 ID·버전은 빈 문자열")
    void bareMusic() {
        TrackFeatureRow row = source.load(id());

        assertThat(row.playable()).isFalse();
        assertThat(row.durationMs()).isNull();
        assertThat(row.track()).isEmpty();
        assertThat(row.analysisVersion()).isEmpty();
        assertThat(row.genres()).isEmpty();
        assertThat(source.loadAll()).containsKey(id());
    }

}
