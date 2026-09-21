package com.osundosun.mupid.api.recommendation.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.recommendation.service.DailyRecommendationBatch;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.recommendation.service.DailyRecommendationService;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 오늘의 추천. 홈 화면의 "오늘의 Mupid Mix" 가 쓴다.
 *
 * 추천을 **만드는** 경로는 없다. 파이프라인이 표를 채우면 여기서 읽힌다.
 */
@RestController
@RequestMapping("/api/v1/recommendation")
@RequiredArgsConstructor
public class DailyRecommendationController {

    private final DailyRecommendationService recommendationService;
    private final DailyRecommendationBatch recommendationBatch;

    /**
     * @param date 기준일(`YYYY-MM-DD`). 비우면 오늘. 그날 추천이 없으면 가장
     *             최근 추천이 있는 날로 되돌아가고, 응답의 `date` 가 그 날짜다.
     *             저장된 추천이 하나도 없으면 온보딩 취향으로 그 자리에서 고른
     *             묶음을 돌려준다 — 저장하지 않으므로 `recommendation_id` 는 0 이다
     */
    @GetMapping("/daily")
    public ResponseEntity<BaseResponse<DailyRecommendationResponse>> getDaily(
        Authentication auth,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            recommendationService.getDaily((User)auth.getPrincipal(), date)
        ));
    }

    /**
     * 그날치를 지금 만든다. **운영용이다.**
     *
     * <p>배치는 매일 정오에 돌지만, 그날 추천 서버가 흔들려 실패하면 다음 날까지 아무도
     * 오늘의 모먼트를 못 본다. 다시 돌릴 길이 없으면 배포를 해야 한다.
     *
     * @param date  기준일. 비우면 오늘
     * @param force 이미 만들어 둔 사용자도 다시 만든다
     */
    @SystemRole
    @PostMapping("/daily/generate")
    public ResponseEntity<BaseResponse<Map<String, Object>>> generateDaily(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(defaultValue = "false") boolean force
    ) {
        LocalDate target = date != null ? date : LocalDate.now();
        int made = recommendationBatch.run(target, force);
        return ResponseEntity.ok(BaseResponse.of(
            "오늘의 모먼트를 만들었습니다.",
            Map.of("date", target.toString(), "generated", made)));
    }

}
