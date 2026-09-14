package com.osundosun.mupid.api.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.report.entity.Report;

public interface ReportRepository extends JpaRepository<Report, Integer> {
    
}
