package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

public interface MusicSectionAnalysisRepository extends JpaRepository<
    MusicSectionAnalysis, Integer
> {
    
}
