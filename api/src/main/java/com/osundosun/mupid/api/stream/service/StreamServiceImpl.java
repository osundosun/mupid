package com.osundosun.mupid.api.stream.service;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.error.StreamErrorCode;
import com.osundosun.mupid.api.stream.repository.StreamRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class StreamServiceImpl implements StreamService {

    private final MusicService musicService;
    private final StreamRepository streamRepository;

    @Override
    public Stream getStreamByMusicId(int musicId) {
        if (!musicService.isExists(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
        return streamRepository.findByMusicId(musicId)
            .orElseThrow(() -> new BusinessException(StreamErrorCode.STREAM_NOT_FOUND));
    }
    
}
