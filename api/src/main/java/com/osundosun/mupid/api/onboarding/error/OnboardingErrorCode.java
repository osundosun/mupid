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
    ONBOARDING_OPTION_EMPTY(HttpStatus.SERVICE_UNAVAILABLE, "온보딩 설문 항목이 준비되지 않았습니다.");

    private final HttpStatus status;
    private final String message;

}
