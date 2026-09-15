package com.osundosun.mupid.api.history.service;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.history.response.RecentSearchResponse;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface HistoryService {

    /**
     * 재생 기록 한 건.
     *
     * @param reason 추천으로 들어온 재생이면 그 사유. 없으면 null
     * @return 남겼으면 true. 없는 곡이면 false
     */
    boolean recordPlay(Integer userId, int musicId, Selector selector, String reason);

    /** 검색 기록 한 건. 빈 검색어는 남기지 않는다. */
    boolean recordSearch(Integer userId, String keyword);

    /** 최근 들은 곡. 같은 곡은 한 번만, 최신순. */
    MusicPageResponse getPlayHistory(User user, int page, int size);

    /** 최근 검색어. 같은 말은 한 번만, 최신순. */
    RecentSearchResponse getRecentSearches(User user, int size);

}
