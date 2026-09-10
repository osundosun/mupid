package com.osundosun.mupid.api.stream.response;

public record StreamGetResponse(

    Integer streamId,

    Integer musicId,

    Integer bitrateKbps,

    String codec,

    Integer durationMs,

    Integer chunkCount,

    String status

) {
    
}
