package com.osundosun.mupid.api.vector;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.sync.entity.AnalyticsSyncPending;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository.Fingerprint;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 미반영 {@code VECTOR} 와 벡터 쪽 MySQL 문장을 <b>실제 MySQL</b> 로 (S15P21A204-457).
 *
 * <p>시험마다 롤백된다. 구간 등록이 같은 트랜잭션에 {@code VECTOR} 행을 남기는지, 배치가 읽는 목록 문장
 * (JPQL {@code LIMIT})과 관문·대조의 집계가 도는지 본다. 지우는 쪽(쓴 뒤 revision 으로)은
 * {@code VectorServiceImplTest} 가 본다 — 새 트랜잭션이라 롤백되는 이 시험에서는 행이 안 보인다.
 */
@SpringBootTest
@Transactional
class VectorPendingMysqlTest {

    @Autowired
    private MusicSectionService sectionService;

    @Autowired
    private AnalyticsSyncPendingRepository pendingRepository;

    @Autowired
    private VectorSectionRepository vectorSections;

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
        List<MusicSectionBulkLine> lines = IntStream.range(0, sections)
            .mapToObj(i -> new MusicSectionBulkLine(i, uid + "#" + i, SectionLabel.VERSE,
                i * 10_000, (i + 1) * 10_000, null, null, null))
            .toList();
        sectionService.replaceSections(music.getMusicId(), new MusicSectionBulkRequest(version, lines));
        em.flush();
    }

    @Test
    @DisplayName("구간 등록은 같은 트랜잭션에 VECTOR 미반영을 남기고, 배치가 읽는 목록에 나온다")
    void sectionRegistrationLeavesVectorPending() {
        replace("v1", 2);
        int musicId = music.getMusicId();

        assertThat(pendingRepository.findRevision(musicId, "VECTOR")).contains(1L);
        List<AnalyticsSyncPending> page = pendingRepository.findPage(AnalyticsSyncKind.VECTOR, musicId - 1, 10);
        assertThat(page).isNotEmpty();
        assertThat(page.get(0).getId().getMusicId()).isEqualTo(musicId);
        assertThat(page.get(0).getAnalysisVersion()).isEqualTo("v1");
        assertThat(pendingRepository.findPage(AnalyticsSyncKind.VECTOR, musicId, 10))
            .noneMatch(row -> row.getId().getMusicId() == musicId);

        replace("v2", 2);                        // 재분석 — 기다리는 버전이 바뀌고 revision 이 오른다
        assertThat(pendingRepository.findRevision(musicId, "VECTOR")).contains(2L);
        assertThat(pendingRepository.deleteIfRevision(musicId, "VECTOR", 1L)).isZero();
        assertThat(pendingRepository.deleteIfRevision(musicId, "VECTOR", 2L)).isEqualTo(1);
    }

    @Test
    @DisplayName("관문 분모·대조 지문·구간 존재 확인은 살아 있는 곡만 센다")
    void aliveOnlyQueries() {
        replace("v1", 3);
        long before = vectorSections.countAliveSections();
        Map<String, Fingerprint> mine = vectorSections.aliveFingerprints().get(music.getMusicId());
        assertThat(mine).hasSize(3);
        assertThat(mine.get(uid + "#1")).isEqualTo(new Fingerprint("v1", 10_000L, 20_000L));
        assertThat(vectorSections.knownExternalIds(List.of(uid + "#0", uid + "#9"))).containsExactly(uid + "#0");

        music.setIsDeleted(true);
        em.flush();
        assertThat(vectorSections.countAliveSections()).isEqualTo(before - 3);
        assertThat(vectorSections.aliveFingerprints()).doesNotContainKey(music.getMusicId());
        // 삭제된 곡의 구간도 "있는 구간" 이다 — 재계산 묶음이 그 곡을 담아도 404 가 아니다.
        assertThat(vectorSections.knownExternalIds(List.of(uid + "#0"))).containsExactly(uid + "#0");
    }

}
