package com.osundosun.mupid.api.music.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum VocalGender {

    M("남성"),
    F("여성"),
    ETC("기타");

    private final String displayName;
    
}
