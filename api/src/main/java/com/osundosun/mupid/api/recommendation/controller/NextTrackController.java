package com.osundosun.mupid.api.recommendation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.recommendation.request.NextTrackRequest;
import com.osundosun.mupid.api.recommendation.response.NextTrackResponse;
import com.osundosun.mupid.api.recommendation.service.NextTrackService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 이어 듣기 — 지금까지의 재생 이력을 보고 다음 곡을 고른다.
 *
 * <p>화면이 부르는 유일한 경로다. <b>브라우저가 추천 서버를 직접 부르지 않는다</b> —
 * `/internal` 은 내부망 전용이고 인증 모델이 다르다.
 *
 * <p>홈의 "당신을 위한 추천" 도 이 경로다. 목록 길이(`limit`)만 다르다.
 */
@RestController
@RequestMapping("/api/v1/recommendation")
@RequiredArgsConstructor
public class NextTrackController {

    private final NextTrackService nextTrackService;

    /**
     * 다음에 들을 곡.
     *
     * <p><b>`user_id` 를 본문으로 받지 않는다.</b> 토큰에서 꺼낸다 — 받으면 남의 추천을
     * 받아 갈 수 있다.
     *
     * <p>조건에 맞는 곡이 없으면 빈 목록에 `shortfall` 을 채워 <b>200</b> 이다.
     * 억지로 채우면 조건과 무관한 곡이 나가고 그게 더 나쁘다.
     */
    @PostMapping("/next")
    public ResponseEntity<BaseResponse<NextTrackResponse>> next(
        Authentication auth,
        @Valid @RequestBody(required = false) NextTrackRequest request
    ) {
        User user = (User)auth.getPrincipal();
        NextTrackRequest body = request == null
            ? new NextTrackRequest(null, null, null, null)
            : request;
        return ResponseEntity.ok(BaseResponse.of(
            nextTrackService.next(user.getUserId(), body)));
    }

}
