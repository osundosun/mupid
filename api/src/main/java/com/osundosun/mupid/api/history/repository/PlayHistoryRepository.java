package com.osundosun.mupid.api.history.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.history.entity.PlayHistory;

public interface PlayHistoryRepository extends JpaRepository<PlayHistory, Integer> {
    
}
