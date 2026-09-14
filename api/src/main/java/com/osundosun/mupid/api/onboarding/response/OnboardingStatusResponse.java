package com.osundosun.mupid.api.onboarding.response;

import java.time.LocalDateTime;
import java.util.List;

import com.osundosun.mupid.api.onboarding.dto.CodeOption;

/**
 * 온보딩 제출 여부와 제출한 내용.
 *
 * 화면 진입 분기에 쓴다 — `submitted` 가 거짓이면 온보딩 화면으로 보낸다.
 * 아직 제출하지 않았으면 `submittedAt` 은 `null`, 목록은 빈 배열이다.
 */
public record OnboardingStatusResponse(
    boolean submitted,
    LocalDateTime submittedAt,
    String nickname,
    List<CodeOption> moods,
    List<CodeOption> genres
) {

}
