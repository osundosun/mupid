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

    /**
     * 한 사용자의 그날 「오늘의 모먼트」를 만들어 저장한다.
     *
     * <p>추천 서버를 한 번 부르고 결과를 통째로 저장한다. 화면은 저장된 것만 읽으므로
     * 하루 동안 같은 곡이 나온다.
     *
     * <p>만들지 못하는 경우가 오류만 있는 것은 아니다 — 추천 결과가 없거나, 추천된 곡이
     * DB 에 없거나, 대표 분위기를 정하지 못하면 <b>거짓</b>을 돌려주고 넘어간다.
     * `mood_code_id` 가 NOT NULL 이라 없는 값을 지어내면 화면이 틀린 분위기를 말한다.
     *
     * @param force 이미 있어도 지우고 다시 만든다
     * @return 새로 만들었으면 참
     */
    boolean generate(int userId, LocalDate date, boolean force);

}
