package com.osundosun.mupid.api.onboarding.response;

import java.time.LocalDateTime;
import java.util.List;

import com.osundosun.mupid.api.onboarding.dto.CodeOption;

/**
 * 한 사용자의 온보딩 설문 결과. **`ROLE_SYSTEM` 전용이다.**
 *
 * 추천 서버(`ai_server`)가 이력이 없는 신규 사용자의 첫 추천을 만들 때 쓰는
 * 콜드스타트 입력이다. **닉네임은 싣지 않는다** — 추천에 쓰이지 않는 값을 배치
 * 페이로드에 실을 이유가 없다. 화면이 쓰는 것은 `GET /api/v1/user` 쪽이다.
 *
 * `userId` 를 되돌려 주는 것은 배치가 여러 사용자를 돌 때 응답만 보고 누구
 * 것인지 알 수 있게 하려는 것이다.
 *
 * 아직 제출하지 않았으면 `submitted` 가 거짓이고 `submittedAt` 은 `null`,
 * 목록은 빈 배열이다. **없는 사용자와 구별해야 한다** — 그쪽은 404 다.
 */
public record OnboardingResultResponse(
    Integer userId,
    boolean submitted,
    LocalDateTime submittedAt,
    List<CodeOption> moods,
    List<CodeOption> genres
) {

}
