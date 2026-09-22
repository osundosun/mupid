package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
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
import com.osundosun.mupid.api.music.error.WaveformErrorCode;
import com.osundosun.mupid.api.music.request.WaveformReplaceRequest;
import com.osundosun.mupid.api.music.response.WaveformReplaceResponse;
import com.osundosun.mupid.api.music.response.WaveformResponse;
import com.osundosun.mupid.api.music.service.WaveformService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 파형이 <b>실제 스토리지와 실제 스키마</b>를 왕복하는지 본다.
 *
 * <p>단위 시험({@code WaveformServiceImplTest})은 스토리지와 DB 를 대역으로 두므로
 * 여기서만 잡히는 것이 있다.
 *
 * <ol>
 *   <li><b>새 ENUM 값이 DB 에 실제로 들어가는가.</b> {@code file_type = 'WAVEFORM'} ·
 *       {@code category = 'WAVEFORM'} 은 2026-09-22 마이그레이션이 넣은 것이다.
 *       DDL 이 적용되지 않은 DB 에서는 여기서 {@code Data truncated} 가 난다 —
 *       {@code ddl-auto: none} 이라 앱이 스키마를 고쳐 주지 않는다.</li>
 *   <li><b>mime 이 잘리지 않는가.</b> {@code application/json} 은 16자다. 컬럼이
 *       {@code VARCHAR(15)} 로 남아 있으면 INSERT 가 깨진다.</li>
 *   <li><b>올린 바이트를 그대로 되읽는가.</b> 직렬화와 역직렬화가 같은 형식을 쓰는지는
 *       한쪽만 고쳐졌을 때 대역으로는 드러나지 않는다.</li>
 * </ol>
 *
 * <p>DB 는 테스트마다 롤백되지만 <b>스토리지 오브젝트는 롤백되지 않는다.</b>
 * 그래서 {@code @AfterEach} 가 직접 지운다.
 */
@SpringBootTest
@Transactional
class WaveformStorageRoundTripTest {

    @Autowired
    private WaveformService waveformService;

    @Autowired
    private StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private String storageKey;

    @BeforeEach
    void setUp() {
        /* albums·artists 는 이미지 파일이 필수다 (image_file_id NOT NULL) */
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.ALBUM)
            .originalFileName("cover.jpg")
            .storageObjectKey("image/album/" + UUID.randomUUID())
            .mime("image/jpeg")
            .fileSizeBytes(1)
            .build();
        em.persist(image);

        Artist artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);

        Album album = Album.builder()
            .artist(artist)
            .title("Blue Static")
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        music = Music.builder()
            .album(album)
            .artist(artist)
            .title("푸른 오후")
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(music);
        em.flush();

        storageKey = "music/%d/waveform.json".formatted(music.getMusicId());
    }

    @AfterEach
    void cleanUp() {
        // 롤백은 DB 행만 되돌린다. 오브젝트는 남으므로 직접 치운다.
        storageService.deleteObjectQuietly(storageKey);
    }

    @Test
    @DisplayName("올린 파형이 스토리지를 왕복하고 곡이 그 파일을 가리킨다")
    void roundTripsThroughRealStorage() {
        List<Double> values = ramp(160);

        WaveformReplaceResponse written = waveformService.replaceWaveform(
            music.getMusicId(), new WaveformReplaceRequest(values));
        em.flush();

        assertEquals(160, written.sampleCount());
        assertTrue(written.fileId() > 0);

        // 스토리지에 실제로 올라갔는가 — 행이 아니라 오브젝트를 본다.
        assertTrue(storageService.statObject(storageKey).isPresent(),
            "오브젝트가 %s 에 없다".formatted(storageKey));

        // 곡 → 파일 참조가 섰는가. 컬럼과 FK 가 없으면 여기서 깨진다.
        Music reloaded = em.find(Music.class, music.getMusicId());
        assertNotNull(reloaded.getWaveformFile());
        assertEquals(storageKey, reloaded.getWaveformFile().getStorageObjectKey());
        // mime 이 VARCHAR(15) 로 남아 있으면 잘린 값이 돌아온다.
        assertEquals("application/json", reloaded.getWaveformFile().getMime());
        assertEquals(FileType.WAVEFORM, reloaded.getWaveformFile().getFileType());
        assertEquals(FileCategory.WAVEFORM, reloaded.getWaveformFile().getCategory());

        WaveformResponse read = waveformService.getWaveform(music.getMusicId());
        assertEquals(music.getMusicId(), read.musicId());
        assertEquals(160, read.sampleCount());
        assertEquals(values, read.values());
    }

    @Test
    @DisplayName("다시 올려도 파일 번호가 그대로다 — 같은 키를 덮어쓴다")
    void reuploadKeepsFileId() {
        int first = waveformService
            .replaceWaveform(music.getMusicId(), new WaveformReplaceRequest(ramp(160)))
            .fileId();
        em.flush();

        int second = waveformService
            .replaceWaveform(music.getMusicId(), new WaveformReplaceRequest(ramp(64)))
            .fileId();
        em.flush();

        assertEquals(first, second);
        assertEquals(64, waveformService.getWaveform(music.getMusicId()).sampleCount());
    }

    @Test
    @DisplayName("파형을 올린 적 없는 곡은 404 다")
    void missingWaveformIsNotFound() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> waveformService.getWaveform(music.getMusicId()));

        assertEquals(WaveformErrorCode.WAVEFORM_NOT_FOUND, thrown.getErrorCode());
    }

    /** 0 에서 1 까지 고르게 오르는 값. 마지막 칸이 1.0 이라 정규화 규칙과 맞는다. */
    private static List<Double> ramp(int count) {
        List<Double> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(Math.round((double)i / (count - 1) * 10000) / 10000.0);
        }
        return values;
    }

}
