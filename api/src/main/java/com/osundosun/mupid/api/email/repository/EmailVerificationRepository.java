package com.osundosun.mupid.api.email.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.email.entity.EmailVerification;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Integer> {
    
}
