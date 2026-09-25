package com.osundosun.mupid.api.user.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {
    
    /**
     * 이메일 인증이 필요합니다.
     */
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),

    /**
     * 이메일이 중복되었습니다.
     */
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "이메일이 중복되었습니다."),
    
    /**
     * 닉네임이 올바르지 않습니다.
     */
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "닉네임은 공백만으로 지을 수 없습니다."),

    /**
     * 요청한 유저를 찾을 수 없습니다.
     */
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 유저를 찾을 수 없습니다."),

    /**
     * 요청한 유저가 <b>탈퇴</b>했습니다. <b>410</b> 이다.
     *
     * <p>탈퇴는 행을 지우지 않고 `status` 를 `WITHDRAW` 로 옮긴다
     * (`deleteUser`, [decision/0013](../../knowledge/decision/0013-soft-delete-policy.md)).
     * 그래서 "그런 회원이 없다" 와 "탈퇴했다" 를 나눌 수 있다.
     *
     * <p>보낸 쪽이 할 일이 다르다 — 앞은 번호를 고쳐야 하고, 뒤는 그 계정을
     * 포기해야 한다. 배치·AI 도구가 탈퇴 회원 번호를 들고 있으면 여기서 멈춘다.
     */
    USER_GONE(HttpStatus.GONE, "요청한 유저는 탈퇴했습니다."),

    /**
     * 시스템 계정(ROLE_SYSTEM)이 아닙니다.
     *
     * <p>관리자의 토큰 발급·폐기(`/api/v1/user/system/{userId}/token`)는 배치 계정만 대상으로 한다.
     * 사람 계정의 토큰을 관리자가 꺼내 쓰는 길이 되면 안 된다.
     */
    NOT_SYSTEM_USER(HttpStatus.BAD_REQUEST, "시스템 계정이 아닙니다.");

    private final HttpStatus status;
    private final String message;

}
