package com.osundosun.mupid.api.history.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    /**
     * 최근 검색어 한 줄 지우기. 그 말로 남은 기록을 전부 지운다.
     *
     * 지울 말은 경로가 아니라 쿼리로 받는다. 검색어에는 `/` 도 `#` 도 들어갈 수
     * 있어서 경로 변수로 두면 인코딩 실수 하나에 다른 엔드포인트로 새거나
     * 404 가 난다.
     *
     * `keyword` 를 빼고 부르면 400 이다. 빈 값은 "전부" 가 아니다.
     */
    @DeleteMapping("/search")
    public ResponseEntity<BaseResponse<Void>> deleteSearchHistory(
        Authentication auth,
        @RequestParam(defaultValue = "") String keyword
    ) {
        historyService.deleteRecentSearch((User)auth.getPrincipal(), keyword);
        return ResponseEntity.ok(BaseResponse.message("최근 검색어를 지웠습니다."));
    }

    /**
     * 최근 검색어 전체 지우기.
     *
     * 파라미터 없는 `DELETE /search` 를 전체 삭제로 쓰지 않는다. 그러면 화면이
     * `keyword` 를 빠뜨린 것과 전부 지우려는 것이 서버에서 똑같아 보인다.
     * 되돌릴 수 없는 쪽은 길을 따로 낸다.
     */
    @DeleteMapping("/search/all")
    public ResponseEntity<BaseResponse<Void>> clearSearchHistory(Authentication auth) {
        historyService.clearRecentSearches((User)auth.getPrincipal());
        return ResponseEntity.ok(BaseResponse.message("최근 검색어를 모두 지웠습니다."));
    }

}
