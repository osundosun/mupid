package com.osundosun.mupid.api.stream.service;

import java.util.List;

import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;

public interface ChunkService {

    Chunk getChunk(int streamId, int index);

    List<Chunk> getChunkAll(int streamId);

    ChunkStorageUrl getStorageUrl(int streamId, int index);
    
}
