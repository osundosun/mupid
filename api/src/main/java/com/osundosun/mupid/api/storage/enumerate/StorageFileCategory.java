package com.osundosun.mupid.api.storage.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum StorageFileCategory {
    
    PROFILE("프로필 이미지"),
    ARTIST("아티스트 이미지"),
    ALBUM("앨범 커버 이미지"),
    STREAMING("스트리밍 음원");

    private final String displayName;

}
