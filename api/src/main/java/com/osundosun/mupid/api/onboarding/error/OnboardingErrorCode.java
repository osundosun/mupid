package com.osundosun.mupid.api.onboarding.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum OnboardingErrorCode implements ErrorCode {

    /**
     * 없는 코드 번호이거나, 분위기 자리에 장르를 보내는 등 용도가 어긋난 경우.
     */
    ONBOARDING_INVALID_CODE(HttpStatus.BAD_REQUEST, "선택할 수 없는 항목이 있습니다."),

    /**
     * 고를 수 있는 항목이 아직 등록되지 않은 경우. 운영에서 `codes` 를 채워야 한다.
     */
    ONBOARDING_OPTION_EMPTY(HttpStatus.SERVICE_UNAVAILABLE, "온보딩 설문 항목이 준비되지 않았습니다."),

    /**
     * 이미 설문을 마친 경우. 온보딩은 회원가입 뒤 한 번만 한다.
     *
     * 409 인 것은 요청 자체는 멀쩡한데 **지금 상태와 충돌**하기 때문이다.
     * 같은 요청을 고쳐 다시 보낸다고 통하지 않으므로 400 이 아니다.
     */
    ONBOARDING_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "온보딩 설문은 한 번만 제출할 수 있습니다.");

    private final HttpStatus status;
    private final String message;

}
