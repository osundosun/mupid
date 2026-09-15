package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.dto.MusicCommentSummary;
import com.osundosun.mupid.api.music.request.MusicCommentUpdateRequest;
import com.osundosun.mupid.api.music.service.MusicCommentService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 시점 댓글 — 댓글 하나를 가리키는 것.
 *
 * 댓글 번호만으로 찾을 수 있으므로 곡 번호를 경로에 다시 넣지 않는다.
 * 본인 것만 고치고 지울 수 있다.
 */
@RestController
@RequestMapping("/api/v1/comment/{commentId}")
@RequiredArgsConstructor
public class CommentController {

    private final MusicCommentService commentService;

    /** 내용만 바꾼다. 시점은 바꿀 수 없다. */
    @PatchMapping
    public ResponseEntity<BaseResponse<MusicCommentSummary>> updateComment(
        Authentication auth,
        @PathVariable int commentId,
        @Valid @RequestBody MusicCommentUpdateRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "댓글을 수정했습니다.",
            commentService.updateComment((User)auth.getPrincipal(), commentId, request)
        ));
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> deleteComment(
        Authentication auth,
        @PathVariable int commentId
    ) {
        commentService.deleteComment((User)auth.getPrincipal(), commentId);
        return ResponseEntity.ok(BaseResponse.message("댓글을 삭제했습니다."));
    }

}
