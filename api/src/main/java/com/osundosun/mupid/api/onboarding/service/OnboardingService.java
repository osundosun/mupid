package com.osundosun.mupid.api.onboarding.service;

import com.osundosun.mupid.api.onboarding.request.OnboardingSubmitRequest;
import com.osundosun.mupid.api.onboarding.response.OnboardingOptionResponse;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface OnboardingService {

    /** 고를 수 있는 분위기·장르 목록. 로그인 없이도 볼 수 있다. */
    OnboardingOptionResponse getOptions();

    /**
     * 제출했는가. 화면 진입 분기에만 쓰는 값이라 **내용은 돌려주지 않는다.**
     *
     * `GET /api/v1/user` 가 이걸 실어 보낸다. 제출 내용을 읽는 것은 추천
     * 서버뿐이고 그쪽은 {@link #getResult(int)} 를 쓴다.
     */
    boolean hasSubmitted(int userId);

    /**
     * 사용자 번호로 읽는 온보딩 결과. 배치(`ROLE_SYSTEM`)가 쓴다.
     *
     * 아직 제출하지 않았으면 빈 결과를 돌려주고, **없는 사용자면
     * `USER_NOT_FOUND` 를 던진다.** 부르는 쪽이 둘을 갈라야 하기 때문이다.
     */
    OnboardingResultResponse getResult(int userId);

    /**
     * 설문을 저장하고 **계정을 `ACTIVE` 로 올린다.** 제출은 **한 번만 할 수 있다.**
     *
     * 가입 직후 상태는 `PENDING` 이고, 그 상태를 벗는 길은 이 제출 하나뿐이다.
     * 저장과 상태 전환은 같은 트랜잭션에 있다.
     *
     * 이미 제출했으면 `ONBOARDING_ALREADY_SUBMITTED` 를 던진다.
     */
    void submit(User user, OnboardingSubmitRequest request);

}
