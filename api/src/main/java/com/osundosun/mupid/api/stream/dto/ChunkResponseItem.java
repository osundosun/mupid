package com.osundosun.mupid.api.stream.dto;

public record ChunkResponseItem(
    
    Integer index,

    Integer durationMs,

    String fileHash

) {
    
}
