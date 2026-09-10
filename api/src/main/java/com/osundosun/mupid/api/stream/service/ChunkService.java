package com.osundosun.mupid.api.stream.service;

import java.util.List;

import com.osundosun.mupid.api.stream.entity.Chunk;

public interface ChunkService {

    Chunk getChunk(int streamId, int index);

    List<Chunk> getChunkRange(int streamId, int from, int to);

    String getChunkUrl(int chunkId);
    
}
