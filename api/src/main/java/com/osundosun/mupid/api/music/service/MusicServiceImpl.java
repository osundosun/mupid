package com.osundosun.mupid.api.music.service;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class MusicServiceImpl implements MusicService {

    private final MusicRepository repository;

    @Override
    public Music getMusic(int musicId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getMusic'");
    }
    
}
