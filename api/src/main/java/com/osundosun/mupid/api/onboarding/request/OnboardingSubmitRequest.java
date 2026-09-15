package com.osundosun.mupid.api.onboarding.request;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 온보딩 설문 제출.
 *
 * 분위기·장르 모두 **하나 이상** 골라야 한다. `onboarding_survey` 의
 * `mood_code_id` · `genre_code_id` 가 둘 다 NOT NULL 이라 한쪽만으로는
 * 행을 만들 수 없다.
 *
 * **닉네임은 받지 않는다.** 회원가입(`UserCreateRequest`)이 이미 2~10자로
 * 받고 있어 같은 값을 두 번 묻는 꼴이었다. 닉네임을 고치는 길은
 * `PATCH /api/v1/user` 하나다.
 */
public record OnboardingSubmitRequest(

    @NotEmpty(message = "분위기를 하나 이상 선택해주세요.")
    List<Integer> moodCodeIds,

    @NotEmpty(message = "장르를 하나 이상 선택해주세요.")
    List<Integer> genreCodeIds

) {

}
