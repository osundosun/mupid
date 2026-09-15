package com.osundosun.mupid.api.onboarding.service;

import com.osundosun.mupid.api.onboarding.request.OnboardingSubmitRequest;
import com.osundosun.mupid.api.onboarding.response.OnboardingOptionResponse;
import com.osundosun.mupid.api.onboarding.response.OnboardingStatusResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface OnboardingService {

    /** 고를 수 있는 분위기·장르 목록. 로그인 없이도 볼 수 있다. */
    OnboardingOptionResponse getOptions();

    /** 제출 여부와 제출한 내용. */
    OnboardingStatusResponse getStatus(User user);

    /** 설문을 저장한다. 이미 제출했으면 갈아 끼운다. */
    void submit(User user, OnboardingSubmitRequest request);

}
