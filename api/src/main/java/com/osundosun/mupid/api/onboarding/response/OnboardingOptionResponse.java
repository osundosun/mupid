package com.osundosun.mupid.api.onboarding.response;

import java.util.List;

import com.osundosun.mupid.api.onboarding.dto.CodeOption;

/** 온보딩 설문에서 고를 수 있는 항목들. */
public record OnboardingOptionResponse(
    List<CodeOption> moods,
    List<CodeOption> genres
) {

}
