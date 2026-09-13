package com.osundosun.mupid.api.stream.dto;

public record StreamResponseItem(
    
    Integer streamId,

    Integer musicId,

    Integer bitrateKbps,

    String codec,

    Integer durationMs,

    Integer chunkCount,

    String status

) {
    
}
