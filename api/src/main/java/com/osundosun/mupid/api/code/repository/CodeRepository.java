package com.osundosun.mupid.api.code.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.code.entity.Code;

public interface CodeRepository extends JpaRepository<Code, Integer> {
    
}
