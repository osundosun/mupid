package com.osundosun.mupid.api.onboarding.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.onboarding.request.OnboardingSubmitRequest;
import com.osundosun.mupid.api.onboarding.response.OnboardingOptionResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩 설문.
 *
 * 첫 방문자는 이 설문을 마쳐야 앱에 들어간다. 회원가입 뒤 **한 번만** 답하고
 * 고치지 않는다 — 이력이 없는 사람의 첫 추천을 만드는 콜드스타트 입력이다.
 *
 * **본인이 제출 내용을 다시 읽는 길은 없다.** 화면은 답을 되돌려 받을 일이
 * 없고, 진입 분기에 필요한 "제출했는가" 는 `GET /api/v1/user` 의
 * `onboarding_submitted` 로 온다. 제출 내용을 읽는 것은 추천 서버뿐이고
 * 그쪽은 `GET /api/v1/user/{userId}/onboarding`(`ROLE_SYSTEM`)을 쓴다.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    /**
     * 고를 수 있는 분위기·장르 목록.
     *
     * 온보딩 화면은 로그인 전에도 뜨므로 인증을 걸지 않는다.
     */
    @GetMapping("/option")
    public ResponseEntity<BaseResponse<OnboardingOptionResponse>> getOptions() {
        return ResponseEntity.ok(BaseResponse.of(onboardingService.getOptions()));
    }

    /**
     * 설문 제출. **한 번만 할 수 있다.**
     *
     * 이 설문은 이력이 없는 사람의 첫 추천을 만드는 콜드스타트 입력이라 계속
     * 고쳐 가며 쓰는 값이 아니다. 이미 제출했으면 409 다.
     *
     * 제출이 성공하면 계정 상태가 `PENDING` 에서 `ACTIVE` 로 바뀐다 —
     * 가입 직후의 `PENDING` 을 벗는 길은 이 호출 하나뿐이다.
     */
    @PostMapping
    public ResponseEntity<BaseResponse<Void>> submit(
        Authentication auth,
        @Valid @RequestBody OnboardingSubmitRequest request
    ) {
        onboardingService.submit((User)auth.getPrincipal(), request);
        return ResponseEntity.ok(BaseResponse.message("취향을 저장했습니다."));
    }

}
