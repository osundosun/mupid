package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.MusicAnalysis;

public interface MusicAnalysisRepository extends JpaRepository<MusicAnalysis, Integer> {
    
}
