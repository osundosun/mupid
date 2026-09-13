package com.osundosun.mupid.api.onboarding.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.onboarding.entity.OnboardingSurvey;

public interface OnboardingSurveyRepository extends JpaRepository<OnboardingSurvey, Integer> {
    
}
