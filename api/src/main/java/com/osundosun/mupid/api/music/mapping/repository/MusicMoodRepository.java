package com.osundosun.mupid.api.music.mapping.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.mapping.entity.MusicMood;

public interface MusicMoodRepository extends JpaRepository<MusicMood, MusicMood.MusicMoodId> {
    
}
