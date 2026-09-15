package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicReactionRequest;
import com.osundosun.mupid.api.music.service.MusicReactionService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 좋아요·관심없음.
 *
 * 한 곡에 한 사람의 반응은 하나다. 설정은 `PUT`, 해제는 `DELETE` 다 —
 * 둘 다 여러 번 보내도 결과가 같다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/reaction")
@RequiredArgsConstructor
public class MusicReactionController {

    private final MusicReactionService reactionService;

    @PutMapping
    public ResponseEntity<BaseResponse<Void>> setReaction(
        Authentication auth,
        @PathVariable int musicId,
        @Valid @RequestBody MusicReactionRequest request
    ) {
        reactionService.setReaction((User)auth.getPrincipal(), musicId, request.reactionType());
        return ResponseEntity.ok(BaseResponse.message("반응을 저장했습니다."));
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> clearReaction(
        Authentication auth,
        @PathVariable int musicId
    ) {
        reactionService.clearReaction((User)auth.getPrincipal(), musicId);
        return ResponseEntity.ok(BaseResponse.message("반응을 해제했습니다."));
    }

}
