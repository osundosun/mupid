package com.osundosun.mupid.api.history.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.history.response.RecentSearchResponse;
import com.osundosun.mupid.api.history.service.HistoryService;
import com.osundosun.mupid.api.history.service.HistoryServiceImpl;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicServiceImpl;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 최근 감상과 최근 검색어.
 *
 * 적재 API 는 따로 없다. 청취 이벤트(`POST /api/v1/events`)의 `PLAY_START` ·
 * `SEARCH` 를 서버가 이 표들에도 남긴다.
 */
@RestController
@RequestMapping("/api/v1/user/history")
@RequiredArgsConstructor
public class UserHistoryController {

    private final HistoryService historyService;

    /** 최근 들은 곡. 같은 곡은 한 번만, 최신순. */
    @GetMapping
    public ResponseEntity<BaseResponse<MusicPageResponse>> getHistory(
        Authentication auth,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + MusicServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            historyService.getPlayHistory((User)auth.getPrincipal(), page, size)
        ));
    }

    /**
     * 최근 검색어. 같은 말은 한 번만, 최신순.
     *
     * 검색창을 눌렀을 때 아래에 펼쳐지는 목록이라 페이지네이션이 없다.
     */
    @GetMapping("/search")
    public ResponseEntity<BaseResponse<RecentSearchResponse>> getSearchHistory(
        Authentication auth,
        @RequestParam(defaultValue = "" + HistoryServiceImpl.DEFAULT_RECENT_SEARCH_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            historyService.getRecentSearches((User)auth.getPrincipal(), size)
        ));
    }

}
