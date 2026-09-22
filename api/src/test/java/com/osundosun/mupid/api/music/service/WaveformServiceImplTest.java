package com.osundosun.mupid.api.music.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.WaveformFile;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.error.WaveformErrorCode;
import com.osundosun.mupid.api.music.request.WaveformReplaceRequest;
import com.osundosun.mupid.api.music.response.WaveformReplaceResponse;
import com.osundosun.mupid.api.music.response.WaveformResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageService;

/**
 * 파형 등록·조회의 서비스 계층을 못박는다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * <ol>
 *   <li><b>디스크에 굳는 형식.</b> 한번 올라간 파일은 앞으로도 읽혀야 한다. 그래서
 *       바이트를 눈으로 확인한다 — 키({@code music/{id}/waveform.json}), snake_case
 *       필드명, 소수 자릿수까지.</li>
 *   <li><b>전부 0 인 파형은 받지 않는다.</b> 형식은 맞지만 계산이 실패한 배치가 올린
 *       것이다. 받아 두면 화면이 폴백하지 않고 평평한 선을 그린다 — <b>고장이
 *       고장처럼 보이지 않게 된다.</b></li>
 *   <li><b>다시 올려도 같은 자리다.</b> 배치가 중간에 죽어도 그대로 다시 돌릴 수
 *       있어야 한다.</li>
 *   <li><b>파형이 없는 곡과 깨진 파형은 다른 오류다.</b> 앞은 정상(404, 화면이 직접
 *       계산한다), 뒤는 어긋난 것(500)이다. 하나로 접으면 스토리지가 비어도 아무도
 *       모른다.</li>
 * </ol>
 *
 * <p>스토리지와 DB 는 대역을 쓴다. 실제 왕복은 Bruno 컬렉션으로 확인한다.
 */
class WaveformServiceImplTest {

    private static final int MUSIC_ID = 12;
    private static final String KEY = "music/12/waveform.json";

    private MusicService musicService;
    private StorageService storageService;
    private ObjectMapper objectMapper;
    private WaveformServiceImpl service;

    private Music music;

    @BeforeEach
    void setUp() {
        musicService = mock(MusicService.class);
        storageService = mock(StorageService.class);
        /* 서비스가 쓰는 것과 같은 **맨 매퍼**다. 파일 형식이 전역 설정에 묶여
         * 있지 않다는 것이 이 테스트의 전제이기도 하다 — 이름은 `WaveformFile` 의
         * `@JsonProperty` 가 정한다. */
        objectMapper = new ObjectMapper();
        service = new WaveformServiceImpl(musicService, storageService);

        music = Music.builder().title("푸른 오후").build();
        when(musicService.requireAlive(MUSIC_ID)).thenReturn(music);
    }

    // ── 등록 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("곡 번호로 만든 키에 JSON 을 올리고 곡이 그 파일을 가리킨다")
    void uploadsToDerivedKeyAndLinksMusic() {
        ObjectStorageFile file = storageFile(4821);
        when(storageService.uploadBytes(any(), any(), anyString(), anyString(), anyString(), any()))
            .thenReturn(file);

        WaveformReplaceResponse result = service.replaceWaveform(MUSIC_ID, request(values(160, 0.5)));

        verify(storageService).uploadBytes(
            eq(FileType.WAVEFORM), eq(FileCategory.WAVEFORM),
            eq(KEY), eq("waveform.json"), eq(WaveformServiceImpl.WAVEFORM_MIME), any());
        assertSame(file, music.getWaveformFile());
        assertEquals(MUSIC_ID, result.musicId());
        assertEquals(160, result.sampleCount());
        assertEquals(4821, result.fileId());
    }

    @Test
    @DisplayName("올라간 바이트는 snake_case JSON 이고 소수 넷째 자리까지다")
    void writesCanonicalJson() {
        when(storageService.uploadBytes(any(), any(), anyString(), anyString(), anyString(), any()))
            .thenReturn(storageFile(1));

        List<Double> raw = values(16, 0.0);
        raw.set(0, 1.0);
        raw.set(1, 0.30000000000000004);
        raw.set(2, 0.123456789);
        service.replaceWaveform(MUSIC_ID, request(raw));

        String json = new String(capturedBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sample_count\":16"), json);
        assertTrue(json.contains("0.3,"), "부동소수 꼬리가 그대로 적혔다: " + json);
        assertTrue(json.contains("0.1235"), "넷째 자리에서 반올림하지 않았다: " + json);
    }

    @Test
    @DisplayName("1 을 넘거나 0 보다 작은 값은 0~1 로 가둔다")
    void clampsOutOfRangeValues() {
        when(storageService.uploadBytes(any(), any(), anyString(), anyString(), anyString(), any()))
            .thenReturn(storageFile(1));

        List<Double> raw = values(16, 0.5);
        raw.set(0, 3.5);
        raw.set(1, -2.0);
        service.replaceWaveform(MUSIC_ID, request(raw));

        List<Double> stored = storedValues();
        assertEquals(1.0, stored.get(0));
        assertEquals(0.0, stored.get(1));
    }

    @Test
    @DisplayName("전부 0 인 파형은 거절한다 — 계산이 실패한 배치가 올린 것이다")
    void rejectsAllZeroWaveform() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.replaceWaveform(MUSIC_ID, request(values(160, 0.0))));

        assertEquals(WaveformErrorCode.INVALID_WAVEFORM, thrown.getErrorCode());
    }

    @Test
    @DisplayName("칸 수가 하한보다 적으면 거절한다 — 애너테이션을 지나 서비스를 직접 불러도")
    void rejectsTooFewSamples() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.replaceWaveform(MUSIC_ID, request(values(8, 0.5))));

        assertEquals(WaveformErrorCode.INVALID_WAVEFORM, thrown.getErrorCode());
    }

    @Test
    @DisplayName("같은 본문을 두 번 보내면 같은 키에 같은 바이트가 간다")
    void isIdempotent() {
        when(storageService.uploadBytes(any(), any(), anyString(), anyString(), anyString(), any()))
            .thenReturn(storageFile(1));

        List<Double> raw = values(160, 0.25);
        service.replaceWaveform(MUSIC_ID, request(raw));
        byte[] first = capturedBytes();
        service.replaceWaveform(MUSIC_ID, request(raw));
        byte[] second = capturedBytes();

        assertEquals(new String(first, StandardCharsets.UTF_8),
            new String(second, StandardCharsets.UTF_8));
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("올린 파형을 그대로 되읽는다")
    void readsBackWhatItWrote() {
        when(storageService.uploadBytes(any(), any(), anyString(), anyString(), anyString(), any()))
            .thenReturn(storageFile(1));

        List<Double> raw = values(160, 0.5);
        raw.set(0, 1.0);
        service.replaceWaveform(MUSIC_ID, request(raw));

        // 바이트를 먼저 꺼내 둔다. `when(...)` 의 인자 안에서 `verify` 를 부르면
        // 스터빙 도중에 같은 목을 건드려 UnfinishedStubbingException 이 난다.
        byte[] written = capturedBytes();
        when(storageService.getFileDataByKey(KEY)).thenReturn(Optional.of(written));

        WaveformResponse response = service.getWaveform(MUSIC_ID);

        assertEquals(MUSIC_ID, response.musicId());
        assertEquals(160, response.sampleCount());
        assertEquals(1.0, response.values().get(0));
        assertEquals(0.5, response.values().get(1));
    }

    @Test
    @DisplayName("파형이 아직 없는 곡은 404 — 빈 배열이 아니다")
    void missingWaveformIsNotFound() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.getWaveform(MUSIC_ID));

        assertEquals(WaveformErrorCode.WAVEFORM_NOT_FOUND, thrown.getErrorCode());
    }

    @Test
    @DisplayName("행은 있는데 오브젝트가 없으면 404 가 아니라 500 이다")
    void danglingRowIsUnreadable() {
        music.setWaveformFile(storageFile(1));
        when(storageService.getFileDataByKey(KEY)).thenReturn(Optional.empty());

        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.getWaveform(MUSIC_ID));

        assertEquals(WaveformErrorCode.WAVEFORM_UNREADABLE, thrown.getErrorCode());
    }

    @Test
    @DisplayName("저장된 JSON 이 깨져 있으면 500 이다")
    void brokenJsonIsUnreadable() {
        music.setWaveformFile(storageFile(1));
        when(storageService.getFileDataByKey(KEY))
            .thenReturn(Optional.of("{ not json".getBytes(StandardCharsets.UTF_8)));

        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.getWaveform(MUSIC_ID));

        assertEquals(WaveformErrorCode.WAVEFORM_UNREADABLE, thrown.getErrorCode());
    }

    @Test
    @DisplayName("삭제된 곡은 파형을 묻기 전에 걸린다")
    void goneMusicStopsBeforeStorage() {
        when(musicService.requireAlive(anyInt()))
            .thenThrow(new BusinessException(MusicErrorCode.MUSIC_GONE));

        BusinessException thrown = assertThrows(BusinessException.class,
            () -> service.getWaveform(MUSIC_ID));

        assertEquals(MusicErrorCode.MUSIC_GONE, thrown.getErrorCode());
    }

    // ── 거들기 ──────────────────────────────────────────────────────────────

    private static List<Double> values(int count, double value) {
        return new ArrayList<>(Collections.nCopies(count, value));
    }

    private static WaveformReplaceRequest request(List<Double> values) {
        return new WaveformReplaceRequest(values);
    }

    private static ObjectStorageFile storageFile(int fileId) {
        return ObjectStorageFile.builder()
            .fileId(fileId)
            .fileType(FileType.WAVEFORM)
            .category(FileCategory.WAVEFORM)
            .originalFileName("waveform.json")
            .storageObjectKey(KEY)
            .mime(WaveformServiceImpl.WAVEFORM_MIME)
            .fileSizeBytes(1)
            .build();
    }

    /** 마지막으로 스토리지에 넘긴 바이트. */
    private byte[] capturedBytes() {
        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(storageService, atLeastOnce()).uploadBytes(
            any(), any(), anyString(), anyString(), anyString(), captor.capture());
        byte[] bytes = captor.getValue();
        assertNotNull(bytes);
        return bytes;
    }

    private List<Double> storedValues() {
        try {
            return objectMapper.readValue(capturedBytes(), WaveformFile.class).values();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
