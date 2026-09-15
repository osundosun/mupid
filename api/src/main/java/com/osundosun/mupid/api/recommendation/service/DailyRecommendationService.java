package com.osundosun.mupid.api.recommendation.service;

import java.time.LocalDate;

import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.user.entity.User;

public interface DailyRecommendationService {

    /**
     * 그날의 추천. 없으면 가장 최근 추천이 있는 날로 되돌아간다.
     *
     * @param date null 이면 오늘
     */
    DailyRecommendationResponse getDaily(User user, LocalDate date);

}
