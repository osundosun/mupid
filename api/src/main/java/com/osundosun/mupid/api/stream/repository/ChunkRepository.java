package com.osundosun.mupid.api.stream.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Chunk;

public interface ChunkRepository extends JpaRepository<Chunk, Chunk.ChunkId> {

    List<Chunk> findByChunkId_StreamId(int streamId);

    Optional<Chunk> findByChunkId(Chunk.ChunkId chunkId);

    long countByChunkId_StreamId(int streamId);

}
