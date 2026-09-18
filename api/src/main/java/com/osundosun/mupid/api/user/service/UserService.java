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

    /**
     * 회원 탈퇴. **행을 지우지 않는다.**
     *
     * <p>`users.user_id` 를 참조하는 테이블이 11개라 하드 삭제는 FK 에 막혀 실패하고
     * (`ai_chat_sessions` 만 `ON DELETE CASCADE` 라 조용히 함께 지워진다), 탈퇴 뒤에도
     * 참조가 살아 있어야 청취 이벤트 등 분석 데이터가 고아가 되지 않는다 —
     * `knowledge/decision/0013-soft-delete-policy.md`.
     *
     * <p>그래서 상태만 `WITHDRAW` 로 옮기고, 자격증명인 비밀번호는 함께 무력화한다
     * ({@link UserServiceImpl#WITHDRAWN_PASSWORD_PLACEHOLDER}). 조회·로그인·토큰
     * 재발급은 이미 `findByUserIdAndStatusNot(..., WITHDRAW)` 로 탈퇴 계정을 걸러내므로
     * 이 전이 하나로 세 입구가 동시에 닫힌다.
     *
     * <p>이미 탈퇴한 계정은 {@link #getUser(int)} 가 내주지 않아 `USER_NOT_FOUND` 다.
     * 두 번 탈퇴는 오류다.
     */
    void deleteUser(int userId);

    boolean isAdminUserExists();
    
}
