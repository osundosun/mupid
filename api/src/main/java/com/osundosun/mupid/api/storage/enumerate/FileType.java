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
    WAVEFORM("파형");

    private final String displayName;
    
}
