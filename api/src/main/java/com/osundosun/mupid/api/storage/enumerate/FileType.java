package com.osundosun.mupid.api.storage.enumerate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FileType {

    IMG("이미지"),
    MUSIC("음악"),

    /**
     * 곡의 파형 JSON. 이미지도 음원도 아니라 자리를 따로 둔다.
     *
     * <p>{@code StorageImageService} 는 {@link #IMG} 만 내보내므로, 이 종류는
     * 이미지 조회 경로로 새어 나가지 않는다.
     */
    WAVEFORM("파형"),

    /**
     * 모델 가중치 파일 (S15P21A204-453). 배치용 Object Storage API 로만 오간다.
     *
     * <p>이미지 경로는 {@link #IMG} 만 내보내고 지우므로, 번호를 알아도 이미지 API 로
     * 가중치를 받거나 지울 수 없다.
     */
    MODEL("모델 가중치");

    private final String displayName;
    
}
