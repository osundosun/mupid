package com.osundosun.mupid.api.stream.response;

import java.util.List;

import com.osundosun.mupid.api.stream.dto.ChunkResponseItem;

public record ChunkGetResponse(

    List<ChunkResponseItem> chunks

) {
    
}
