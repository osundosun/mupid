package com.osundosun.mupid.api.music.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.WaveformFile;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.WaveformErrorCode;
import com.osundosun.mupid.api.music.request.WaveformReplaceRequest;
import com.osundosun.mupid.api.music.response.WaveformReplaceResponse;
import com.osundosun.mupid.api.music.response.WaveformResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.service.StorageService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class WaveformServiceImpl implements WaveformService {

    /**
     * 파형 파일의 mime.
     *
     * <p>`object_storage_files.mime` 이 `VARCHAR(15)` 이던 시절에는 이 값(16자)이
     * 들어가지 않아 짧은 이름을 써야 했다. 2026-09-22 에 `VARCHAR(64)` 로 넓혔다
     * (domain/erd.md 의 "2026-09-22 — 파형").
     */
    public static final String WAVEFORM_MIME = "application/json";

    /** 저장할 때 남기는 소수 자릿수. */
    private static final int SCALE = 4;

    private static final double ROUND_UNIT = Math.pow(10, SCALE);

    /**
     * 애플리케이션 매퍼를 주입받지 않는다. Spring Boot 4 의 자동설정은 Jackson 3 만
     * 빈으로 등록해서 `com.fasterxml` 의 `ObjectMapper` 빈이 <b>없다</b>
     * (`PlayQueueRepository` · `EventServiceImpl` 도 같은 이유로 직접 만든다).
     *
     * <p>주입받을 수 있었더라도 쓰지 않았을 것이다. 이 JSON 은 <b>스토리지에 굳는
     * 저장 형식</b>이라 응답 규약(`spring.jackson.property-naming-strategy`)에
     * 묶이면 안 된다 — 그 설정을 누가 바꾸면 이미 올라간 파형을 못 읽는다.
     * 필드 이름은 {@link WaveformFile} 이 직접 적는다.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MusicService musicService;
    private final StorageService storageService;

    @Override
    @Transactional(readOnly = true)
    public WaveformResponse getWaveform(int musicId) {
        // 곡이 없으면 404, 삭제됐으면 410 이다 (`requireAlive` 가 가른다).
        Music music = musicService.requireAlive(musicId);

        ObjectStorageFile file = music.getWaveformFile();
        if (file == null) {
            throw new BusinessException(WaveformErrorCode.WAVEFORM_NOT_FOUND);
        }

        // 행이 가리키는데 오브젝트가 없다면 "아직 없다" 가 아니라 어긋난 것이다.
        byte[] bytes = storageService.getFileDataByKey(file.getStorageObjectKey())
            .orElseThrow(() -> new BusinessException(WaveformErrorCode.WAVEFORM_UNREADABLE));

        WaveformFile stored;
        try {
            stored = MAPPER.readValue(bytes, WaveformFile.class);
        } catch (Exception e) {
            throw new BusinessException(WaveformErrorCode.WAVEFORM_UNREADABLE);
        }
        if (stored.values() == null || stored.values().isEmpty()) {
            throw new BusinessException(WaveformErrorCode.WAVEFORM_UNREADABLE);
        }

        return new WaveformResponse(musicId, stored.values().size(), stored.values());
    }

    @Override
    public WaveformReplaceResponse replaceWaveform(int musicId, WaveformReplaceRequest request) {
        Music music = musicService.requireAlive(musicId);

        // 검증 애너테이션이 먼저 걸러 주지만, 서비스를 직접 부르는 길(시험·배치
        // 코드)에서도 규칙이 지켜져야 한다.
        List<Double> values = normalize(request.values());

        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(new WaveformFile(values.size(), values));
        } catch (JsonProcessingException e) {
            // 숫자 목록이라 여기까지 올 일이 없다. 삼키면 원인을 잃으므로 드러낸다.
            throw new IllegalStateException("파형 직렬화에 실패했습니다.", e);
        }

        ObjectStorageFile file = storageService.uploadBytes(
            FileType.WAVEFORM,
            FileCategory.WAVEFORM,
            storageKey(musicId),
            "waveform.json",
            WAVEFORM_MIME,
            bytes);

        // 같은 키면 위에서 기존 행을 갱신하므로 파일 번호가 그대로다. 그래도
        // 매번 세워 두는 것은 곡에 참조가 아직 없는 첫 등록 때문이다.
        music.setWaveformFile(file);

        return new WaveformReplaceResponse(musicId, values.size(), file.getFileId());
    }

    /**
     * 저장할 모양으로 다듬는다.
     *
     * <p>배치가 보낸 문자열을 그대로 두지 않고 <b>서버가 다시 직렬화</b>한다.
     * 파일 모양을 정하는 곳이 한 군데여야 나중에 형식을 바꿀 때 올린 쪽마다
     * 찾아다니지 않는다.
     */
    private List<Double> normalize(List<Double> raw) {
        if (raw.size() < WaveformReplaceRequest.MIN_SAMPLES
            || raw.size() > WaveformReplaceRequest.MAX_SAMPLES) {
            throw new BusinessException(WaveformErrorCode.INVALID_WAVEFORM);
        }

        List<Double> values = raw.stream()
            .map(WaveformServiceImpl::round)
            .toList();

        // 전부 0 이면 그려 봐야 평평한 선이다. 계산이 실패한 배치가 빈 파형을
        // 올린 것으로 보고 거절한다 — 받아 두면 화면이 폴백조차 하지 않는다.
        if (values.stream().allMatch(value -> value == 0.0)) {
            throw new BusinessException(WaveformErrorCode.INVALID_WAVEFORM);
        }

        return values;
    }

    /**
     * 0~1 로 가두고 소수 넷째 자리에서 반올림한다.
     *
     * <p>파형은 눈으로 보는 값이라 자릿수를 줄여도 그림이 달라지지 않는다. 배치가
     * 보내는 `double` 을 그대로 적으면 한 곡이 `0.30000000000000004` 같은 값으로
     * 채워져 파일이 두 배가 된다.
     *
     * <p>유한하지 않은 값(NaN·무한대)은 0 으로 둔다. 검증에서 걸리지만
     * (`@DecimalMin` 이 NaN 을 통과시키지 않는다) 서비스를 직접 부르는 길이 있다.
     */
    private static double round(Double value) {
        if (value == null || !Double.isFinite(value)) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return Math.round(clamped * ROUND_UNIT) / ROUND_UNIT;
    }

    /**
     * 파형 파일의 저장 키. 곡 하나에 하나뿐이라 청크와 달리 번호가 붙지 않는다.
     * 예) music/12/waveform.json
     */
    private static String storageKey(int musicId) {
        return "music/%d/waveform.json".formatted(musicId);
    }

}
