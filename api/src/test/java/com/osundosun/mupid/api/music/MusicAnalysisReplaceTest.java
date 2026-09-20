package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.enumerate.VocalGender;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisBulkResponse;
import com.osundosun.mupid.api.music.service.MusicAnalysisService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 보컬 분석 적재(`PUT /api/v1/music/{musicId}/analysis`)의 서비스 계층 검증.
 *
 * <p><b>`music_analysis` 는 이 변경 전까지 0행이었다.</b> 조회 쪽
 * (`MusicServiceImpl.getAnalysis`, MuBTI A축)은 이미 이 표를 읽고 있었는데
 * <b>넣는 경로가 없었다.</b> 그래서 조회는 언제나 {@code hasVocal=null} 을 내보냈고,
 * 그 {@code null} 이 "보컬 없음" 인지 "판정 전" 인지 화면이 구분할 수 없었다.
 *
 * <p><b>0행인 표가 처음 쓰일 때 터지는 전례가 있다.</b> `music_genres`·`music_moods`
 * 가 그랬다 — 관계와 `@EmbeddedId` 가 같은 컬럼을 각자 매핑하고 있어서 쓰는 순간
 * `No value specified for parameter` 로 죽었고, 쓴 적이 없어 드러나지 않았다.
 * 이 엔티티도 `@Id @Column` 과 `@MapsId @JoinColumn` 이 같은 컬럼(`music_id`)을
 * 가리키므로, 여기서 <b>실제로 INSERT 가 되는지</b>를 본다.
 *
 * <p>로컬 MySQL 의 실제 스키마를 그대로 쓴다. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class MusicAnalysisReplaceTest {

    @Autowired
    private MusicAnalysisService analysisService;

    @Autowired
    private MusicAnalysisRepository analysisRepository;

    @PersistenceContext
    private EntityManager em;

    private Music music;

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
        em.flush();
        em.clear();
        music = em.find(Music.class, music.getMusicId());
    }

    @Test
    @DisplayName("빈 곡에 보컬 판정을 넣으면 실제로 저장된다 — 이 표가 처음으로 쓰이는 경로다")
    void writesTheFirstRowEver() {
        assertEquals(0, stored().size(), "처음에는 비어 있어야 한다");

        MusicAnalysisBulkResponse result = replace(true, VocalGender.F);

        assertEquals(music.getMusicId(), result.musicId());
        assertEquals(Boolean.TRUE, result.hasVocal());
        assertEquals("F", result.vocalGender());
        assertEquals(false, result.replaced(), "처음 넣으면 덮어쓴 것이 아니다");

        List<MusicAnalysis> rows = stored();
        assertEquals(1, rows.size(), "행이 하나여야 한다");
        assertEquals(Boolean.TRUE, rows.get(0).getHasVocal());
        assertEquals(VocalGender.F, rows.get(0).getVocalGender());
    }

    @Test
    @DisplayName("다시 넣으면 덮어쓴다 — 곡당 한 행이라 두 벌이 생기지 않는다")
    void replacesInsteadOfAppending() {
        replace(true, VocalGender.M);

        MusicAnalysisBulkResponse result = replace(false, null);

        assertTrue(result.replaced(), "두 번째는 이전 판정을 덮어쓴 것이다");
        assertEquals(1, stored().size(), "여전히 한 행이어야 한다");
        assertEquals(Boolean.FALSE, stored().get(0).getHasVocal());
        assertNull(stored().get(0).getVocalGender(), "성별을 못 냈으면 비운다");
    }

    @Test
    @DisplayName("성별을 모르면 null 로 둔다 — ETC 로 뭉개면 판정 실패가 판정 결과가 된다")
    void keepsUnknownGenderNull() {
        replace(true, null);

        MusicAnalysis row = stored().get(0);
        assertEquals(Boolean.TRUE, row.getHasVocal(), "보컬은 있는데");
        assertNull(row.getVocalGender(), "성별은 모른다");
    }

    @Test
    @DisplayName("연주곡은 hasVocal=false, 성별 없음으로 들어간다")
    void instrumentalHasNoGender() {
        MusicAnalysisBulkResponse result = replace(false, null);

        assertEquals(Boolean.FALSE, result.hasVocal());
        assertNull(result.vocalGender());
        assertEquals(Boolean.FALSE, stored().get(0).getHasVocal());
    }

    @Test
    @DisplayName("없는 곡이면 막는다 — 조용히 아무 데도 안 넣고 끝나지 않는다")
    void rejectsUnknownMusic() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> analysisService.replaceAnalysis(Integer.MAX_VALUE,
                new MusicAnalysisRequest(true, VocalGender.M)));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, thrown.getErrorCode());
    }

    @Test
    @DisplayName("세 성별 값이 모두 저장된다 — ENUM('M','F','MIX')")
    void storesEveryGender() {
        for (VocalGender g : VocalGender.values()) {
            replace(true, g);
            assertEquals(g, stored().get(0).getVocalGender(), g + " 가 저장되지 않았다");
        }
    }

    @Test
    @DisplayName("조회가 이 값을 본다 — analyzed=true 에 hasVocal 이 실린다")
    void theReadPathSeesIt() {
        replace(true, VocalGender.F);
        em.flush();
        em.clear();

        MusicAnalysis found = analysisRepository.findById(music.getMusicId()).orElse(null);

        assertNotNull(found, "조회가 행을 찾아야 한다");
        assertEquals(Boolean.TRUE, found.getHasVocal());
        assertEquals(VocalGender.F, found.getVocalGender());
    }

    @Test
    @DisplayName("A축 기준선이 이 행을 센다 — 0행이면 null 이던 값이 나온다")
    void theVocalShareQueryPicksItUp() {
        assertNull(analysisRepository.findVocalShare(), "행이 없으면 null 이다");

        replace(true, VocalGender.F);
        em.flush();

        Double share = analysisRepository.findVocalShare();
        assertNotNull(share, "행이 생기면 값이 나와야 한다");
        assertTrue(share > 0.0, "보컬 있는 곡 하나이므로 1.0 이어야 한다: " + share);
    }

    // ------------------------------------------------------------------ 도우미

    private MusicAnalysisBulkResponse replace(Boolean hasVocal, VocalGender gender) {
        MusicAnalysisBulkResponse result = analysisService.replaceAnalysis(
            music.getMusicId(), new MusicAnalysisRequest(hasVocal, gender));
        em.flush();
        em.clear();
        return result;
    }

    private List<MusicAnalysis> stored() {
        return analysisRepository.findByMusicIdIn(List.of(music.getMusicId()));
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
