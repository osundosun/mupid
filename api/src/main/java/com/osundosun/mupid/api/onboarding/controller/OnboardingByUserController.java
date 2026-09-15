package com.osundosun.mupid.api.onboarding.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 번호로 온보딩 결과를 읽는 API. **배치 전용이다.**
 *
 * 추천 서버(`ai_server`)가 이력이 없는 신규 사용자의 첫 추천을 만들 때 부른다.
 *
 * **제출 내용을 읽는 유일한 길이다.** 본인 조회는 없앴다 — 화면이 답을 되돌려
 * 받을 일이 없어서다. 진입 분기에 필요한 "마쳤는가" 는 `GET /api/v1/user` 가
 * 불리언으로 실어 보낸다. 이쪽은 남의 것을 읽으므로 `@SystemRole` 이 필요하다.
 *
 * **`@SystemRole` 을 클래스에 붙였다.** 이 컨트롤러에는 배치가 아니면 부를 일이
 * 없는 메서드만 둔다. 일반 사용자에게도 열 조회가 생기면 메서드 단위로 내려야
 * 한다 — `LyricByMusicController` 가 그 모양이다.
 *
 * 경로가 `/api/v1/onboarding/{userId}` 가 아닌 것은 바로 옆에
 * `GET /api/v1/onboarding/option` 이 있기 때문이다. 스프링은 리터럴 패턴을 먼저
 * 맞추므로 동작은 하지만, 읽는 사람에게 `option` 이 사용자 번호 자리로 보인다.
 */
@RestController
@RequestMapping("/api/v1/user/{userId}/onboarding")
@RequiredArgsConstructor
@SystemRole
public class OnboardingByUserController {

    private final OnboardingService onboardingService;

    /**
     * 사용자 한 명의 온보딩 결과.
     *
     * 설문을 마치지 않았어도 **404 가 아니라 200** 이다(`submitted: false`).
     * 배치에게 "아직 안 한 사람" 은 건너뛸 일이고, "없는 사용자" 는 데이터가
     * 어긋난 일이라 둘을 갈라야 한다. 없는 번호는 404 `MUPID_USER_NOT_FOUND` 다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<OnboardingResultResponse>> getResult(
        @PathVariable int userId
    ) {
        return ResponseEntity.ok(BaseResponse.of(onboardingService.getResult(userId)));
    }

}
