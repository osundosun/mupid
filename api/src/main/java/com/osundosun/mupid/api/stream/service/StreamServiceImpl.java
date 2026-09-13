package com.osundosun.mupid.api.stream.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.repository.StreamRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class StreamServiceImpl implements StreamService {

    private final MusicService musicService;
    private final StreamRepository streamRepository;

    @Override
    public List<Stream> getStreamByMusicId(int musicId) {
        if (!musicService.isExists(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
        return streamRepository.findAllByMusicId(musicId)
                .stream()
                .filter(it -> it.getStatus().equals(StreamStatus.DONE))
                .toList();
    }
    
}
