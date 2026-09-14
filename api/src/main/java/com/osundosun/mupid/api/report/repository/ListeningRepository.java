package com.osundosun.mupid.api.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.report.entity.Listening;

public interface ListeningRepository extends JpaRepository<Listening, Listening.ListeningId> {
    
}
