package com.osundosun.mupid.api.music.service;

import com.osundosun.mupid.api.music.dto.MusicCommentSummary;
import com.osundosun.mupid.api.music.request.MusicCommentCreateRequest;
import com.osundosun.mupid.api.music.request.MusicCommentUpdateRequest;
import com.osundosun.mupid.api.music.response.MusicCommentPageResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface MusicCommentService {

    /** 한 곡의 댓글. 시점 오름차순. */
    MusicCommentPageResponse getComments(User user, int musicId, int page, int size);

    MusicCommentSummary addComment(User user, int musicId, MusicCommentCreateRequest request);

    MusicCommentSummary updateComment(User user, int commentId, MusicCommentUpdateRequest request);

    /** 소프트 삭제. 본인 것만. */
    void deleteComment(User user, int commentId);

}
