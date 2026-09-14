package com.osundosun.mupid.api.ai.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.ai.entity.AiChatSession;

public interface AiChatSessionRepository extends JpaRepository<AiChatSession, Integer> {
    
}
