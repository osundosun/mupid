package com.osundosun.mupid.api.music.mapping.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;

public interface MusicAnalysisInstrumentRepository extends JpaRepository<
    MusicAnalysisInstrument, MusicAnalysisInstrument.MusicAnalysisInstrumentId
> {
    
}
