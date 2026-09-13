package com.osundosun.mupid.api.stream.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.Codec;

public interface StreamRepository extends JpaRepository<Stream, Integer> {

    List<Stream> findAllByMusicId(int musicId);

    boolean existsByMusicIdAndCodecAndBitrateKbps(int musicId, Codec codec, int bitrateKbps);
    
}
