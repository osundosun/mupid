package com.osundosun.mupid.api.stream.service;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.repository.StreamRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class StreamServiceImpl implements StreamService {

    private final MusicService musicService;
    private final StreamRepository streamRepository;

    @Override
    public Stream getStreamByMusicId(int musicId) {

    }
    
}
