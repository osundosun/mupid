package com.osundosun.mupid.api.music.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.MusicCommentSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicComment;
import com.osundosun.mupid.api.music.error.MusicCommentErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicCommentRepository;
import com.osundosun.mupid.api.music.request.MusicCommentCreateRequest;
import com.osundosun.mupid.api.music.request.MusicCommentUpdateRequest;
import com.osundosun.mupid.api.music.response.MusicCommentPageResponse;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 곡 시점 댓글.
 *
 * 삭제는 소프트 삭제다([decision/0013](../../knowledge/decision/0013-soft-delete-policy.md)).
 * 수정·삭제는 본인 것만 되고, 남의 댓글이면 404 가 아니라 403 을 준다 — 댓글이
 * 있다는 사실 자체는 목록에서 이미 보이므로 숨길 것이 없다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MusicCommentServiceImpl implements MusicCommentService {

    /** 한 번에 내려주는 최대 댓글 수. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;

    private final MusicCommentRepository repository;
    // 곡 생사를 가르는 판정은 `MusicService` 한 곳에 있다. 여기서 리포지토리를
    // 직접 보면 404/410 을 서비스마다 베끼게 되고, 한쪽만 고쳐지면 조용히 갈린다.
    private final MusicService musicService;

    @Override
    @Transactional(readOnly = true)
    public MusicCommentPageResponse getComments(User user, int musicId, int page, int size) {
        requireMusic(musicId);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);

        Page<MusicComment> found = repository.findByMusic(musicId, PageRequest.of(safePage, safeSize));
        List<MusicCommentSummary> items = found.getContent().stream()
            .map(comment -> toSummary(comment, user))
            .toList();

        return new MusicCommentPageResponse(
            items,
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages());
    }

    @Override
    public MusicCommentSummary addComment(User user, int musicId, MusicCommentCreateRequest request) {
        // 삭제된 곡이면 404 가 아니라 410 이다.
        Music music = musicService.requireAlive(musicId);

        MusicComment saved = repository.save(MusicComment.builder()
            .music(music)
            .user(user)
            .timestampMs(request.timestampMs())
            .content(request.content().trim())
            .build());
        return toSummary(saved, user);
    }

    @Override
    public MusicCommentSummary updateComment(User user, int commentId, MusicCommentUpdateRequest request) {
        MusicComment comment = requireOwn(user, commentId);
        comment.setContent(request.content().trim());
        // @LastModifiedDate 는 flush 때 찍힌다. 먼저 내보내지 않으면 응답의
        // updated_at 이 수정 전 값이다.
        repository.flush();
        return toSummary(comment, user);
    }

    @Override
    public void deleteComment(User user, int commentId) {
        requireOwn(user, commentId).setIsDeleted(true);
    }

    private MusicComment requireOwn(User user, int commentId) {
        MusicComment comment = repository.findByMusicCommentIdAndIsDeletedFalse(commentId)
            .orElseGet(() -> {
                // 살아 있는 댓글이 없다. 없는 댓글(404)과 삭제된 댓글(410)을 가른다 —
                // 조회가 `IsDeletedFalse` 로 걸러서 그대로는 둘 다 비어 있다.
                if (repository.findByMusicCommentId(commentId).isPresent()) {
                    throw new BusinessException(MusicCommentErrorCode.COMMENT_GONE);
                }
                throw new BusinessException(MusicCommentErrorCode.COMMENT_NOT_FOUND);
            });
        if (!comment.getUser().getUserId().equals(user.getUserId())) {
            throw new BusinessException(MusicCommentErrorCode.COMMENT_FORBIDDEN);
        }
        return comment;
    }

    /** 곡이 없으면 404, 삭제됐으면 410 이다. */
    private void requireMusic(int musicId) {
        musicService.requireAlive(musicId);
    }

    private MusicCommentSummary toSummary(MusicComment comment, User viewer) {
        User author = comment.getUser();
        return new MusicCommentSummary(
            comment.getMusicCommentId(),
            comment.getMusic().getMusicId(),
            comment.getTimestampMs(),
            comment.getContent(),
            author.getUserId(),
            author.getNickname(),
            author.getUserId().equals(viewer.getUserId()),
            comment.getCreatedAt(),
            comment.getUpdatedAt());
    }

}
