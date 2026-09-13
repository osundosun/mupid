package com.osundosun.mupid.api.stream.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Stream;

public interface StreamRepository extends JpaRepository<Stream, Integer> {

    List<Stream> findAllByMusicId(int musicId);
    
}
