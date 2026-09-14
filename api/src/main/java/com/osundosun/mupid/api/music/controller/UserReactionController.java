package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.enumerate.ReactionType;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.response.MusicReactionResponse;
import com.osundosun.mupid.api.music.service.MusicReactionService;
import com.osundosun.mupid.api.music.service.MusicServiceImpl;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 내가 반응한 곡.
 *
 * 곡에 거는 반응(`/api/v1/music/{musicId}/reaction`)과 달리 "내 것" 을 읽는
 * 경로라 `/user` 아래에 둔다.
 */
@RestController
@RequestMapping("/api/v1/user/reaction")
@RequiredArgsConstructor
public class UserReactionController {

    private final MusicReactionService reactionService;

    /**
     * 반응한 곡 번호.
     *
     * 화면이 어느 목록에서든 하트 상태를 그려야 해서 페이징하지 않는다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<MusicReactionResponse>> getReactions(
        Authentication auth
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            reactionService.getReactions((User)auth.getPrincipal())
        ));
    }

    /**
     * 반응한 곡을 곡 정보까지 붙여서. 최근에 누른 순이다.
     *
     * @param type LIKE 또는 DISLIKE. 기본은 LIKE
     */
    @GetMapping("/music")
    public ResponseEntity<BaseResponse<MusicPageResponse>> getReactedMusic(
        Authentication auth,
        @RequestParam(defaultValue = "LIKE") ReactionType type,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + MusicServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            reactionService.getReactedMusic((User)auth.getPrincipal(), type, page, size)
        ));
    }

}
