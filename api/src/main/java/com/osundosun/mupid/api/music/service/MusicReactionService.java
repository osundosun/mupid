package com.osundosun.mupid.api.music.service;

import com.osundosun.mupid.api.music.enumerate.ReactionType;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.response.MusicReactionResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface MusicReactionService {

    /** 좋아요·관심없음을 설정한다. 같은 값을 다시 보내도 결과가 같다. */
    void setReaction(User user, int musicId, ReactionType type);

    /** 반응을 지운다. 원래 없었어도 오류가 아니다. */
    void clearReaction(User user, int musicId);

    /** 반응한 곡 번호 전부. */
    MusicReactionResponse getReactions(User user);

    /** 반응한 곡을 목록 표현으로. 최근에 누른 순이다. */
    MusicPageResponse getReactedMusic(User user, ReactionType type, int page, int size);

}
