package com.osundosun.mupid.api.storage.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum StorageFileType {
    
    IMG("이미지"),
    MUSIC("음원");

    private final String displayName;

}
