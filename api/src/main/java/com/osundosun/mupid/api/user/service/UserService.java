package com.osundosun.mupid.api.user.service;

import java.util.Optional;

import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;

public interface UserService {

    User createUser(UserCreateRequest dto);

    Optional<User> getUser(int userId);

    Optional<User> getUserByEmail(String email);

    User updateUser(int userId, UserUpdateRequest dto);

    void deleteUser(int userId);
    
}
