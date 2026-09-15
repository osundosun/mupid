package com.osundosun.mupid.api.recommendation.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
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

    /**
     * @param date 기준일(`YYYY-MM-DD`). 비우면 오늘. 그날 추천이 없으면 가장
     *             최근 추천이 있는 날로 되돌아가고, 응답의 `date` 가 그 날짜다
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

}
