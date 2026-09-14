package com.osundosun.mupid.api.history.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.history.entity.SearchHistory;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Integer> {
    
}
