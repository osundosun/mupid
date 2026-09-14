package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;

public interface MusicService {

    int addMusic(MusicAddRequest request);

    Optional<Music> getMusic(int musicId);

    /**
     * 곡 목록. 검색어가 비면 전체다.
     * 화면이 어떤 곡을 재생할지 고르는 유일한 경로다.
     */
    MusicPageResponse searchMusic(String keyword, int page, int size);

    /** 곡 상세. 목록과 같은 표현을 쓴다. */
    MusicSummary getMusicSummary(int musicId);

    boolean isExists(int musicId);

    void updateMusic(int musicId, MusicUpdateRequest request);

    void deleteMusic(int musicId);

}
