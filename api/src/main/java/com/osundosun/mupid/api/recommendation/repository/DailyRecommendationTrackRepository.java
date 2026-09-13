package com.osundosun.mupid.api.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;

public interface DailyRecommendationTrackRepository extends JpaRepository<
    DailyRecommendationTrack,
    DailyRecommendationTrack.DailyRecommendationTrackId
> {
    
}
