package com.osundosun.mupid.api.stream.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.Codec;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;

public interface StreamRepository extends JpaRepository<Stream, Integer> {

    List<Stream> findAllByMusicId(int musicId);

    List<Stream> findAllByMusicIdIn(Collection<Integer> musicIds);

    boolean existsByMusicIdAndCodecAndBitrateKbps(int musicId, Codec codec, int bitrateKbps);

    /** 곡 목록의 재생 길이를 한 번에 구하려고 쓴다. 곡마다 조회하면 N+1 이다. */
    List<Stream> findAllByMusicIdInAndStatus(List<Integer> musicIds, StreamStatus status);
    
}
