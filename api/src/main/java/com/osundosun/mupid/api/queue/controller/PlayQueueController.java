package com.osundosun.mupid.api.queue.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.queue.dto.PlayQueueChange;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;
import com.osundosun.mupid.api.queue.request.PlayQueueReplaceRequest;
import com.osundosun.mupid.api.queue.request.PlayQueueTrackAddRequest;
import com.osundosun.mupid.api.queue.response.PlayQueueResponse;
import com.osundosun.mupid.api.queue.response.PlayQueueTrackAddResponse;
import com.osundosun.mupid.api.queue.service.PlayQueueService;
import com.osundosun.mupid.api.queue.service.PlayQueueViewAssembler;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 내 재생 큐. 저장소는 Redis 이고 규칙은 decision/0022 에 있다.
 *
 * 플레이리스트와 달리 **큐는 사용자당 하나뿐**이라 경로에 번호가 없다.
 */
@RestController
@RequestMapping("/api/v1/user/queue")
@RequiredArgsConstructor
public class PlayQueueController {

    private final PlayQueueService playQueueService;
    private final PlayQueueViewAssembler assembler;

    /**
     * 지금 큐. 곡 정보까지 채워서 나간다.
     *
     * 큐가 없으면 빈 큐(`revision: 0`)다 — 404 가 아니다. 한 번도 담지 않은 것은
     * 오류가 아니라 정상 상태이고, 화면은 그 판본으로 첫 저장을 건다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<PlayQueueResponse>> getQueue(Authentication auth) {
        User user = (User)auth.getPrincipal();
        PlayQueueDocument document = playQueueService.get(user.getUserId());
        return ResponseEntity.ok(BaseResponse.of(assembler.toResponse(document)));
    }

    /**
     * 큐를 통째로 바꾼다. 순서 바꾸기와 재생 위치 이동이 여기로 온다.
     *
     * 화면은 조작할 때마다 부르지 않고 **모아서** 보낸다. 끌어서 순서를 다섯 번
     * 바꿔도 요청은 한 번이다.
     */
    @PutMapping
    public ResponseEntity<BaseResponse<PlayQueueResponse>> replaceQueue(
        Authentication auth,
        @Valid @RequestBody PlayQueueReplaceRequest request
    ) {
        User user = (User)auth.getPrincipal();
        PlayQueueDocument document = playQueueService.replace(
            user.getUserId(),
            request.musicIds(),
            request.currentMusicId(),
            request.revision()
        );
        return ResponseEntity.ok(BaseResponse.of(assembler.toResponse(document)));
    }

    /**
     * 한 곡을 담는다.
     *
     * 이미 담긴 곡이어도 200 이다. 플레이리스트 곡 추가와 같은 규칙이다 —
     * 중복은 실패가 아니라 "이미 원하는 상태" 라 `duplicated` 로 구분한다.
     *
     * 100곡이 차 있으면 **거절하지 않고** 가장 오래 담긴 곡을 뺀다. 무엇이
     * 빠졌는지는 `evicted_music_id` 로 알려 준다.
     */
    @PostMapping("/track")
    public ResponseEntity<BaseResponse<PlayQueueTrackAddResponse>> addTrack(
        Authentication auth,
        @Valid @RequestBody PlayQueueTrackAddRequest request
    ) {
        User user = (User)auth.getPrincipal();
        PlayQueueChange change = playQueueService.add(user.getUserId(), request.musicId());

        return ResponseEntity.ok(BaseResponse.of(
            change.duplicated() ? "이미 재생 목록에 담긴 곡입니다." : "재생 목록에 곡을 담았습니다.",
            new PlayQueueTrackAddResponse(
                assembler.toResponse(change.document()), /* 담은 뒤의 큐 */
                change.duplicated(), /* 중복 여부 */
                change.evictedMusicId() /* 밀려난 곡 */
            )
        ));
    }

    /**
     * 한 곡을 뺀다. 담겨 있지 않으면 404 다.
     */
    @DeleteMapping("/track/{musicId}")
    public ResponseEntity<BaseResponse<PlayQueueResponse>> removeTrack(
        Authentication auth,
        @PathVariable int musicId
    ) {
        User user = (User)auth.getPrincipal();
        PlayQueueDocument document = playQueueService.remove(user.getUserId(), musicId);
        return ResponseEntity.ok(BaseResponse.of(
            "재생 목록에서 곡을 뺐습니다.",
            assembler.toResponse(document)
        ));
    }

    /**
     * 플레이리스트 전체 재생. 큐가 그 수록곡으로 통째로 바뀌고 첫 곡이 재생 대상이 된다.
     *
     * 판본을 받지 않는다. 사람이 방금 누른 동작이라 다른 기기의 상태와 겨루지 않고
     * 이기는 것이 맞다.
     */
    @PostMapping("/playlist/{playlistId}")
    public ResponseEntity<BaseResponse<PlayQueueResponse>> replaceFromPlaylist(
        Authentication auth,
        @PathVariable int playlistId
    ) {
        User user = (User)auth.getPrincipal();
        PlayQueueDocument document = playQueueService.replaceFromPlaylist(user.getUserId(), playlistId);
        return ResponseEntity.ok(BaseResponse.of(
            "재생 목록을 플레이리스트로 바꿨습니다.",
            assembler.toResponse(document)
        ));
    }

}
