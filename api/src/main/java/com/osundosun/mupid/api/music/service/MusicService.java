package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Music;

public interface MusicService {

    Optional<Music> getMusic(int musicId);

    boolean isExists(int musicId);

}
