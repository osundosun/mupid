package com.osundosun.mupid.api.music.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

public interface MusicSectionAnalysisRepository extends JpaRepository<
    MusicSectionAnalysis, Integer
> {

    /** 한 곡의 구간. 순서대로 준다. */
    List<MusicSectionAnalysis> findByMusic_MusicIdOrderByIndexAsc(Integer musicId);

}
