package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.dto.MusicCommentSummary;
import com.osundosun.mupid.api.music.request.MusicCommentCreateRequest;
import com.osundosun.mupid.api.music.response.MusicCommentPageResponse;
import com.osundosun.mupid.api.music.service.MusicCommentService;
import com.osundosun.mupid.api.music.service.MusicCommentServiceImpl;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 시점 댓글 — 곡에 매달린 것.
 *
 * 조회도 인증이 필요하다. 댓글은 재생 화면에서만 보이고 재생 자체가 로그인을
 * 요구한다. 응답의 `mine` 을 채우려면 누가 보는지도 알아야 한다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/comment")
@RequiredArgsConstructor
public class MusicCommentController {

    private final MusicCommentService commentService;

    /** 시점 오름차순. 화면이 파형 위에 늘어놓기 때문이다. */
    @GetMapping
    public ResponseEntity<BaseResponse<MusicCommentPageResponse>> getComments(
        Authentication auth,
        @PathVariable int musicId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + MusicCommentServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            commentService.getComments((User)auth.getPrincipal(), musicId, page, size)
        ));
    }

    @PostMapping
    public ResponseEntity<BaseResponse<MusicCommentSummary>> addComment(
        Authentication auth,
        @PathVariable int musicId,
        @Valid @RequestBody MusicCommentCreateRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "댓글을 남겼습니다.",
            commentService.addComment((User)auth.getPrincipal(), musicId, request)
        ));
    }

}
