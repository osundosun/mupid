package com.osundosun.mupid.api.history.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.history.entity.PlayHistory;
import com.osundosun.mupid.api.history.entity.SearchHistory;
import com.osundosun.mupid.api.history.repository.PlayHistoryRepository;
import com.osundosun.mupid.api.history.repository.SearchHistoryRepository;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.music.service.MusicServiceImpl;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 재생·검색 기록.
 *
 * 적재는 청취 이벤트 수집기(`POST /api/v1/events`)가 부른다. 화면이 같은 사실을
 * 두 번 보내지 않도록 서버가 한 요청에서 갈라 쓴다 —
 * [decision/0018](../../knowledge/decision/0018-history-from-event-stream.md).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class HistoryServiceImpl implements HistoryService {

    /** `search_histories.keyword` 가 varchar(200) 이다. */
    private static final int KEYWORD_MAX = 200;

    /** `play_histories.recommendation_reason` 이 varchar(100) 이다. */
    private static final int REASON_MAX = 100;

    private final PlayHistoryRepository playRepository;
    private final SearchHistoryRepository searchRepository;
    private final MusicRepository musicRepository;
    private final UserRepository userRepository;
    private final MusicSummaryAssembler assembler;

    @Override
    public boolean recordPlay(Integer userId, int musicId, Selector selector, String reason) {
        Music music = musicRepository.findById(musicId).orElse(null);
        // 이벤트의 track_id 가 MySQL 의 music_id 라는 보장이 아직 없다
        // (open-questions C5). 없는 곡이면 조용히 넘긴다 — 기록 하나 때문에
        // 이벤트 수집을 실패시킬 이유가 없다.
        if (music == null || Boolean.TRUE.equals(music.getIsDeleted())) return false;

        playRepository.save(PlayHistory.builder()
            .user(userRepository.getReferenceById(userId))
            .music(music)
            .selector(selector == null ? Selector.USER : selector)
            .recommendationReason(cut(reason, REASON_MAX))
            .build());
        return true;
    }

    @Override
    public boolean recordSearch(Integer userId, String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        if (trimmed.isEmpty()) return false;

        searchRepository.save(SearchHistory.builder()
            .user(userRepository.getReferenceById(userId))
            .keyword(cut(trimmed, KEYWORD_MAX))
            .build());
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPageResponse getPlayHistory(User user, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MusicServiceImpl.MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);

        Page<Integer> ids = playRepository.findRecentMusicIds(
            user.getUserId(), PageRequest.of(safePage, safeSize));
        List<MusicSummary> items = assembler.byIds(ids.getContent());

        return new MusicPageResponse(
            items,
            ids.getNumber(),
            ids.getSize(),
            ids.getTotalElements(),
            ids.getTotalPages());
    }

    /** 컬럼 길이를 넘기면 INSERT 가 터진다. 기록은 잘려도 남는 쪽이 낫다. */
    private String cut(String value, int max) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

}
