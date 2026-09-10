package com.osundosun.mupid.api.stream.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Stream;

public interface StreamRepository extends JpaRepository<Stream, Integer> {

    Optional<Stream> findByMusicId(int musicId);
    
}
