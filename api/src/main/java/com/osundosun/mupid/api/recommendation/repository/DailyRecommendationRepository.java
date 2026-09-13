package com.osundosun.mupid.api.recommendation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;

public interface DailyRecommendationRepository extends JpaRepository<DailyRecommendation, Integer> {
    
}
