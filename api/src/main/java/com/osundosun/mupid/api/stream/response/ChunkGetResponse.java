package com.osundosun.mupid.api.stream.response;

public record ChunkGetResponse(

    Integer index,

    Integer durationMs,

    String fileUrl,

    String fileHash

) {
    
}
