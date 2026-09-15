package com.osundosun.mupid.api.storage.enumerate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FileType {

    IMG("이미지"),
    MUSIC("음악");

    private final String displayName;
    
}
