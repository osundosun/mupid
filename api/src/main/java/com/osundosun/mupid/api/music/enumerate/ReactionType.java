package com.osundosun.mupid.api.music.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ReactionType {

    LIKE("좋아요"),
    DISLIKE("관심없음");

    private final String displayName;
    
}
