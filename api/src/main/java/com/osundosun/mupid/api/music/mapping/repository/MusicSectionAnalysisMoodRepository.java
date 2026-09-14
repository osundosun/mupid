package com.osundosun.mupid.api.music.mapping.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;

public interface MusicSectionAnalysisMoodRepository extends JpaRepository<
    MusicSectionAnalysisMood, MusicSectionAnalysisMood.MusicSectionAnalysisMoodId
> {
    
}
