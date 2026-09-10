package com.osundosun.mupid.api.stream.response;

import java.util.List;

public record ChunkGetRangeResponse(

    List<ChunkGetResponse> chunks

) {
    
}
