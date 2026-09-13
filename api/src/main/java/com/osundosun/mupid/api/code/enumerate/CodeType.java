package com.osundosun.mupid.api.code.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CodeType {

    MOOD("분위기"),
    INSTRUMENT("악기"),
    GENRE("장르");

    private final String displayName;
    
}
