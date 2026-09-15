package com.osundosun.mupid.api.music.request;

public record LyricUpdateRequest(

    Integer musicId,

    String text,

    Integer timeMs

) {
    
}
