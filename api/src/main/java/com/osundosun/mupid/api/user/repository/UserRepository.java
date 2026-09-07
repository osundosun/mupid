package com.osundosun.mupid.api.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.user.entity.User;

public interface UserRepository extends JpaRepository<User, Integer> {

    User findByEmail(String email);
    
}
