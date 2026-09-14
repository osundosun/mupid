package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.entity.Lyric;
import com.osundosun.mupid.api.music.response.LyricResponse;
import com.osundosun.mupid.api.music.request.LyricAddRequest;
import com.osundosun.mupid.api.music.request.LyricUpdateRequest;

public interface LyricService {
    
    /**
     * 가사 생성시 반환값은 Index 입니다.
     */
    int addLyric(LyricAddRequest request);

    Optional<Lyric> getLyric(int lyricId);

    /** 한 곡의 가사. 시간 오름차순. 가사가 없으면 빈 목록이다. */
    LyricResponse getLyrics(int musicId);

    void updateLyric(int lyricId, LyricUpdateRequest request);

    void deleteLyric(int lyricId);

}
