package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.response.MusicAnalysisResponse;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;

import java.util.Collection;

public interface MusicService {

    int addMusic(MusicAddRequest request);

    Optional<Music> getMusic(int musicId);

    /**
     * 곡을 찾되 <b>왜 없는지</b>까지 가른다. 없으면 `MUSIC_NOT_FOUND`(404),
     * 삭제됐으면 `MUSIC_GONE`(410).
     *
     * <p>곡을 참조하는 쓰기·조회 경로가 전부 이걸 쓴다. `getMusic` 은 둘 다
     * `Optional.empty()` 라 구분할 수 없어서, 그대로 쓰면 삭제된 곡이 "없는 곡" 으로
     * 보고된다. 한 곳(`MusicServiceImpl.requireAlive`)에 판정을 모아 두는 이유는
     * 서비스마다 베끼면 한쪽만 고쳐졌을 때 조용히 갈리기 때문이다.
     */
    Music requireAlive(int musicId);

    /**
     * 곡 목록. 검색어가 비면 전체다.
     * 화면이 어떤 곡을 재생할지 고르는 유일한 경로다.
     *
     * @param vocalFilters 탐색 화면의 보컬 필터. 비우면 걸지 않는다(하위 호환)
     */
    MusicPageResponse searchMusic(
        String keyword, Collection<VocalFilter> vocalFilters, int page, int size);

    /**
     * 필터 없는 곡 목록. {@code searchMusic(keyword, null, page, size)} 와 같다.
     *
     * 남겨 두는 이유: 보컬 필터를 모르는 호출부(추천·관리자 등)가 있고, 테스트도
     * 이 계약이 "예전과 완전히 같다" 는 것을 이 시그니처로 증명한다.
     */
    default MusicPageResponse searchMusic(String keyword, int page, int size) {
        return searchMusic(keyword, null, page, size);
    }

    /** 곡 상세. 목록과 같은 표현을 쓴다. */
    MusicSummary getMusicSummary(int musicId);

    /**
     * 곡 분석 결과. 분석 전이면 `analyzed = false` 로 온다 — 404 가 아니다.
     */
    MusicAnalysisResponse getAnalysis(int musicId);

    boolean isExists(int musicId);

    void updateMusic(int musicId, MusicUpdateRequest request);

    void deleteMusic(int musicId);

}
