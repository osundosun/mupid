package com.osundosun.mupid.api.music.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.MusicReaction;
import com.osundosun.mupid.api.music.enumerate.ReactionType;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicReactionRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.response.MusicReactionResponse;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 곡 좋아요·관심없음.
 *
 * 한 사람이 한 곡에 가질 수 있는 반응은 하나다(`music_reactions` 의 복합키).
 * 좋아요에서 관심없음으로 바꾸면 행이 바뀌고, 해제하면 **행을 지운다** —
 * 표 주석이 정한 규약이다.
 *
 * `music.like_count` 는 비정규화된 집계다. 반응이 바뀔 때 같이 움직여야 하고,
 * 읽고-고쳐-쓰면 동시 요청에서 하나가 사라지므로 DB 에서 바로 더하고 뺀다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MusicReactionServiceImpl implements MusicReactionService {

    private final MusicReactionRepository repository;
    private final MusicRepository musicRepository;
    private final MusicService musicService;

    @Override
    public void setReaction(User user, int musicId, ReactionType type) {
        requireMusic(musicId);
        Optional<MusicReaction> found = repository
            .findByMusicReactionId_UserIdAndMusicReactionId_MusicId(user.getUserId(), musicId);

        if (found.isPresent()) {
            MusicReaction reaction = found.get();
            ReactionType before = reaction.getReactionType();
            // 같은 값이면 건드리지 않는다. 같은 요청을 두 번 보내도 결과가 같아야 한다.
            if (before == type) return;
            reaction.setReactionType(type);
            applyLikeDelta(musicId, before, type);
            return;
        }

        repository.save(MusicReaction.builder()
            .musicReactionId(new MusicReaction.MusicReactionId(user.getUserId(), musicId))
            .reactionType(type)
            .build());
        if (type == ReactionType.LIKE) musicRepository.increaseLikeCount(musicId);
    }

    @Override
    public void clearReaction(User user, int musicId) {
        requireMusic(musicId);
        repository.findByMusicReactionId_UserIdAndMusicReactionId_MusicId(user.getUserId(), musicId)
            .ifPresent(reaction -> {
                repository.delete(reaction);
                if (reaction.getReactionType() == ReactionType.LIKE) {
                    musicRepository.decreaseLikeCount(musicId);
                }
            });
    }

    @Override
    @Transactional(readOnly = true)
    public MusicReactionResponse getReactions(User user) {
        return new MusicReactionResponse(
            repository.findMusicIds(user.getUserId(), ReactionType.LIKE),
            repository.findMusicIds(user.getUserId(), ReactionType.DISLIKE));
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPageResponse getReactedMusic(User user, ReactionType type, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MusicServiceImpl.MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);

        Page<Integer> ids = repository.findMusicIdPage(
            user.getUserId(), type, PageRequest.of(safePage, safeSize));
        List<MusicSummary> items = musicService.getMusicSummaries(ids.getContent());

        return new MusicPageResponse(
            items,
            ids.getNumber(),
            ids.getSize(),
            ids.getTotalElements(),
            ids.getTotalPages());
    }

    /**
     * 반응이 바뀔 때 좋아요 수를 맞춘다.
     *
     * `before` 는 이미 바뀐 엔티티에서 읽으면 안 되므로 호출 쪽이 넘긴다.
     */
    private void applyLikeDelta(int musicId, ReactionType before, ReactionType after) {
        if (before == after) return;
        if (after == ReactionType.LIKE) musicRepository.increaseLikeCount(musicId);
        else if (before == ReactionType.LIKE) musicRepository.decreaseLikeCount(musicId);
    }

    /**
     * 없거나 삭제된 곡에는 반응을 남길 수 없다.
     *
     * `Music` 을 통째로 읽지 않는다. 읽어 두면 뒤에서 좋아요 수를 UPDATE 로
     * 올릴 때 영속성 컨텍스트에 낡은 값이 남는다.
     */
    private void requireMusic(int musicId) {
        if (!musicRepository.existsByMusicIdAndIsDeletedFalse(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
    }

}
