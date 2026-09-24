package com.osundosun.mupid.api.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.enumerate.VocalGender;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.Codec;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 추천 후보를 실제 DB 에서 모은다. 질의(JPQL)가 실제 스키마에서 돌고, 후보 조건이 맞게
 * 걸리는지 본다 — 지워진 곡·재생할 수 없는 곡은 빠지고, 새 컬럼(BPM·에너지 등)이 실린다.
 *
 * <p>캐시(1분)를 피하려고 서비스를 저장소로 직접 만든다. 개발 DB 에 다른 곡이 있어도 되게
 * 이 시험이 넣은 곡만 골라 본다.
 */
@SpringBootTest
@Transactional
class RecommenderCatalogServiceTest {

    @Autowired
    private RecommenderCatalogRepository repository;

    @PersistenceContext
    private EntityManager em;

    private String uid;
    private Album album;
    private Artist artist;

    @BeforeEach
    void setUp() {
        uid = "CATT" + UUID.randomUUID().toString().substring(0, 8);
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG).category(FileCategory.ALBUM).originalFileName("c.jpg")
            .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
            .fileSizeBytes(10).mime("image/jpeg").build();
        em.persist(image);
        artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);
        album = Album.builder().artist(artist).title("A").imageFile(image)
            .releaseDate(LocalDateTime.now()).build();
        em.persist(album);
    }

    private Music music(String title, boolean playable, boolean deleted) {
        Music m = Music.builder().album(album).artist(artist).title(title)
            .releaseDate(LocalDateTime.now()).build();
        em.persist(m);
        if (deleted) {
            m.setIsDeleted(true);
        }
        em.persist(Stream.builder().music(m).bitrateKbps(128).codec(Codec.OPUS)
            .durationMs(150_000).chunkCount(10)
            .status(playable ? StreamStatus.DONE : StreamStatus.IN_PROGRESS).build());
        return m;
    }

    private void section(Music m, String track, int index, SectionLabel label, Double energy) {
        em.persist(MusicSectionAnalysis.builder().music(m).sectionLabel(label).index(index)
            .startTimeMs(index * 30_000).endTimeMs(index * 30_000 + 25_000)
            .externalId(track + "#" + index).energy(energy).build());
    }

    private RecommenderCatalog build() {
        em.flush();
        em.clear();
        return new RecommenderCatalogServiceImpl(repository).build();
    }

    @Test
    @DisplayName("살아 있고 재생할 수 있는 곡만 후보다 — 지워진 곡·스트림이 안 끝난 곡은 빠진다")
    void onlyAliveAndPlayableTracks() {
        Music ok = music("재생 가능", true, false);
        section(ok, uid + "A", 0, SectionLabel.CHORUS, 0.08);
        Music notReady = music("변환 중", false, false);
        section(notReady, uid + "B", 0, SectionLabel.VERSE, 0.05);
        Music gone = music("지운 곡", true, true);
        section(gone, uid + "C", 0, SectionLabel.INTRO, 0.02);

        RecommenderCatalog catalog = build();

        List<String> mine = catalog.tracks().stream().map(RecommenderCatalog.Track::track)
            .filter(t -> t.startsWith(uid)).toList();
        assertThat(mine).containsExactly(uid + "A");
        assertThat(catalog.sections()).filteredOn(s -> s.track().startsWith(uid))
            .extracting(RecommenderCatalog.Section::externalId).containsExactly(uid + "A#0");
    }

    @Test
    @DisplayName("분석 값·곡 길이·구간 에너지가 실린다 — 추천 서버가 요청 본문으로 받는 값이다")
    void carriesTheValuesTheRecommenderReads() {
        Music m = music("값 있는 곡", true, false);
        section(m, uid + "V", 0, SectionLabel.CHORUS, 0.0873);
        section(m, uid + "V", 1, SectionLabel.OUTRO, null);
        em.persist(MusicAnalysis.builder().music(m).hasVocal(true).vocalGender(VocalGender.F)
            .bpm(128.5).bpmRequestedMin(120).bpmRequestedMax(130).language("korean")
            .category("female_korean_ballad").build());

        RecommenderCatalog catalog = build();

        RecommenderCatalog.Track t = catalog.tracksById().get(uid + "V");
        assertThat(t).isNotNull();
        assertThat(t.durationMs()).isEqualTo(150_000);
        assertThat(t.bpm()).isEqualTo(128.5);
        assertThat(t.bpmRequestedMin()).isEqualTo(120);
        assertThat(t.language()).isEqualTo("korean");
        assertThat(t.category()).isEqualTo("female_korean_ballad");
        assertThat(t.hasVocal()).isTrue();
        assertThat(t.vocalGender()).isEqualTo("F");
        Map<String, Double> energy = new java.util.HashMap<>();
        catalog.sections().stream().filter(s -> s.track().equals(uid + "V"))
            .forEach(s -> energy.put(s.externalId(), s.energy()));
        assertThat(energy).containsEntry(uid + "V#0", 0.0873).containsEntry(uid + "V#1", null);
    }

    @Test
    @DisplayName("분석 행이 없는 곡도 후보다 — 값만 비어 있다")
    void aTrackWithoutAnalysisIsStillACandidate() {
        Music m = music("분석 전", true, false);
        section(m, uid + "N", 0, SectionLabel.VERSE, null);

        RecommenderCatalog.Track t = build().tracksById().get(uid + "N");

        assertThat(t).isNotNull();
        assertThat(t.bpm()).isNull();
        assertThat(t.hasVocal()).isNull();
    }

}
