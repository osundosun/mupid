package com.osundosun.mupid.api.user.service;

import java.util.Optional;

import com.osundosun.mupid.api.user.dto.SystemUserCreateResult;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.request.SystemUserCreateRequest;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;

public interface UserService {

    User createUser(UserCreateRequest dto);

    /**
     * 배치 프로그램이 쓸 ROLE_SYSTEM 계정을 만든다.
     * 비밀번호는 서버가 만들어 주고, 평문은 이 호출의 반환값에만 들어 있다.
     */
    SystemUserCreateResult createSystemUser(SystemUserCreateRequest dto);

    Optional<User> getUser(int userId);

    Optional<User> getUserByEmail(String email);

    Optional<User> getUserByImageFileId(int imageFileId);

    User updateUser(int userId, UserUpdateRequest dto);

    void deleteUser(int userId);

    boolean isAdminUserExists();
    
}
