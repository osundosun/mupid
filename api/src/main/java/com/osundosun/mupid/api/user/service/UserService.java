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

    /**
     * 온보딩을 마친 사람을 `PENDING` 에서 `ACTIVE` 로 올린다.
     *
     * 가입 직후 상태는 `PENDING` 이고, 그 상태를 벗는 길은 온보딩 제출
     * 하나뿐이다. 이미 `ACTIVE` 면 아무것도 하지 않는다 — 온보딩은 한 번만
     * 받지만, 상태만 어긋난 계정을 다시 부를 수 있어야 한다.
     *
     * 탈퇴한 계정은 `USER_NOT_FOUND` 다 ({@link #getUser(int)} 의 규칙).
     */
    User activateUser(int userId);

    void deleteUser(int userId);

    boolean isAdminUserExists();
    
}
