package com.osundosun.mupid.api.stream.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.entity.Chunk.ChunkId;
import com.osundosun.mupid.api.stream.error.ChunkErrorCode;
import com.osundosun.mupid.api.stream.repository.ChunkRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ChunkServiceImpl implements ChunkService {

    private final ChunkRepository repository;
    private final StorageProperties storageProps;

    @Override
    public Chunk getChunk(int streamId, int index) {
        Chunk chunk = repository.findByChunkId(new ChunkId(streamId, index))
            .orElseThrow(() -> new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND));
        boolean isDeleted = chunk.getStream().getMusic().getIsDeleted();
        if (isDeleted) {
            throw new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND);
        }
        return chunk;
    }

    @Override
    public List<Chunk> getChunkAll(int streamId) {
        List<Chunk> chunks = repository.findByChunkId_StreamId(streamId);
        if (chunks.size() > 0) {
            boolean isDeleted = chunks.get(0).getStream().getMusic().getIsDeleted();
            if (!isDeleted) {
                return chunks;
            }
        }
        return List.of();
    }

    @Override
    public ChunkStorageUrl getStorageUrl(int streamId, int index) {
        Chunk chunk = getChunk(streamId, index);
        ObjectStorageFile chunkFile = chunk.getFile();
        String storageUrl = "/storage/buckets/%s/%s".formatted(
            storageProps.bucketName(),
            chunkFile.getStorageObjectKey());
        return new ChunkStorageUrl(storageUrl, chunkFile.getMime());
    }
    
}
