package com.osundosun.mupid.api.music.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.MusicReaction;

public interface MusicReactionRepository extends JpaRepository<MusicReaction, MusicReaction.MusicReactionId> {
    
}
