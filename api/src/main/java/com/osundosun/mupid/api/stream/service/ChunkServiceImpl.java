package com.osundosun.mupid.api.stream.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.error.ChunkErrorCode;
import com.osundosun.mupid.api.stream.repository.ChunkRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements ChunkService {

    private final ChunkRepository repository;

    @Override
    public Chunk getChunk(int streamId, int index) {
        Chunk chunk = repository.findByStreamIdAndIndex(streamId, index)
            .orElseThrow(() -> new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND));
        boolean isDeleted = chunk.getStream().getMusic().getIsDeleted();
        if (isDeleted) {
            throw new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND);
        }
        return chunk;
    }

    @Override
    public List<Chunk> getChunkRange(int streamId, int from, int to) {
        List<Chunk> chunks = repository.findByStreamIdAndIndexBetween(streamId, from, to);
        if (chunks.size() > 0) {
            boolean isDeleted = chunks.get(0).getStream().getMusic().getIsDeleted();
            if (!isDeleted) {
                return chunks;
            }
        }
        return List.of();
    }

    @Override
    public String getChunkUrl(int chunkId) {
        return "/api/v1/chunk/data/%d".formatted(chunkId);
    }
    
}
