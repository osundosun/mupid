package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor 
public class MusicServiceImpl implements MusicService {

    private final MusicRepository repository;

    @Override
    public Optional<Music> getMusic(int musicId) {
        Music music = repository.findById(musicId).orElse(null);
        if (music == null || music.getIsDeleted()) {
            return null;
        }
        return Optional.of(music);
    }

    @Override
    public boolean isExists(int musicId) {
        Music music = repository.findById(musicId).orElse(null);
        if (music == null || music.getIsDeleted()) {
            return false;
        }
        return true;
    }

}
