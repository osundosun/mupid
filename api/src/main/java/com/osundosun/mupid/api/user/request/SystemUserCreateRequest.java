package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SystemUserCreateRequest(

    /**
     * 배치 계정 이메일. 로그인 식별자로만 쓰고 메일을 보내지는 않는다.
     */
    @NotBlank(message = "이메일이 입력되지 않았습니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email,

    /**
     * 표시용 이름. 비우면 이메일 앞부분을 쓴다 (10자에서 자른다).
     *
     * <p>{@code users.nickname} 이 VARCHAR(10) 이다. 검사가 없으면 긴 이름은 INSERT 에서
     * {@code Data too long} 으로 500 이 된다.
     */
    @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
    String nickname

) {
    
}
