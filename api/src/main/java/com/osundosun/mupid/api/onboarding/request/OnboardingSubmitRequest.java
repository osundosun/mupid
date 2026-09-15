package com.osundosun.mupid.api.onboarding.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 온보딩 설문 제출.
 *
 * 분위기·장르 모두 **하나 이상** 골라야 한다. `onboarding_survey` 의
 * `mood_code_id` · `genre_code_id` 가 둘 다 NOT NULL 이라 한쪽만으로는
 * 행을 만들 수 없다.
 */
public record OnboardingSubmitRequest(

    @NotBlank(message = "닉네임을 입력해주세요.")
    @Size(min = 2, max = 10, message = "닉네임은 2~10자 사이어야 합니다.")
    String nickname,

    @NotEmpty(message = "분위기를 하나 이상 선택해주세요.")
    List<Integer> moodCodeIds,

    @NotEmpty(message = "장르를 하나 이상 선택해주세요.")
    List<Integer> genreCodeIds

) {

}
