package com.osundosun.mupid.api.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

public interface UserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUserIdAndStatusNot(int userId, UserStatus status);

    Optional<User> findByEmailAndStatusNot(String email, UserStatus status);

    Optional<User> findByImageFileId(Integer imageFileId);

    boolean existsByRole(UserRole role);
    
}
