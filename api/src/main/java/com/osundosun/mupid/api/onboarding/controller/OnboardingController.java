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
import com.osundosun.mupid.api.onboarding.response.OnboardingStatusResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 온보딩 설문.
 *
 * 첫 방문자는 이 설문을 마쳐야 앱에 들어간다. 지금까지 결과가 브라우저에만
 * 남아 기기를 바꾸면 사라졌다.
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

    /** 제출 여부. 화면 진입 분기에 쓴다. */
    @GetMapping
    public ResponseEntity<BaseResponse<OnboardingStatusResponse>> getStatus(
        Authentication auth
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            onboardingService.getStatus((User)auth.getPrincipal())
        ));
    }

    /** 설문 제출. 이미 제출했으면 갈아 끼운다. */
    @PostMapping
    public ResponseEntity<BaseResponse<Void>> submit(
        Authentication auth,
        @Valid @RequestBody OnboardingSubmitRequest request
    ) {
        onboardingService.submit((User)auth.getPrincipal(), request);
        return ResponseEntity.ok(BaseResponse.message("취향을 저장했습니다."));
    }

}
