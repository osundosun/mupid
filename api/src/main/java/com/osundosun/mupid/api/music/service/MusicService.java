package com.osundosun.mupid.api.music.service;

import java.util.List;
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

    /**
     * 주어진 번호의 곡들을 목록과 같은 표현으로 바꾼다.
     *
     * 순서는 넘긴 번호 순서를 지킨다. 삭제된 곡은 빠지므로 결과가 더 짧을 수 있다.
     * 좋아요 목록처럼 "이미 번호를 알고 있는" 화면이 쓴다.
     */
    List<MusicSummary> getMusicSummaries(List<Integer> musicIds);

    boolean isExists(int musicId);

    void updateMusic(int musicId, MusicUpdateRequest request);

    void deleteMusic(int musicId);

}
