package com.osundosun.mupid.api.user.response;

import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

/**
 * 내 계정 정보.
 *
 * `onboardingSubmitted` 는 **화면 진입 분기**를 위해 실려 있다. 온보딩을
 * 마치지 않았으면 설문 화면으로 보내야 하는데, 그 판단에 필요한 것은 불리언
 * 하나지 제출 내용이 아니다. 내용을 읽는 길은 추천 서버 전용
 * (`GET /api/v1/user/{userId}/onboarding`) 하나뿐이다.
 */
public record UserGetInfoResponse(

    Integer userId,

    String email,

    String nickname,

    String imageUrl,

    Integer birthYear,

    UserGender gender,

    UserRole role,

    UserStatus status,

    /** 온보딩 설문을 마쳤는가. 마치지 않았으면 화면이 설문으로 보낸다. */
    boolean onboardingSubmitted

) {
    
}
