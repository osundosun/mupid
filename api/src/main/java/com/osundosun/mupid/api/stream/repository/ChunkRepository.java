package com.osundosun.mupid.api.stream.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Chunk;

public interface ChunkRepository extends JpaRepository<Chunk, Integer> {

    List<Chunk> findByStreamId(int streamId);

    Optional<Chunk> findByStreamIdAndIndex(int streamId, int index);

    List<Chunk> findByStreamIdAndIndexBetween(int streamId, int minIndex, int maxIndex);
    
}
