package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SystemUserCreateRequest(

    /**
     * 배치 계정 이메일. 로그인 식별자로만 쓰고 메일을 보내지는 않는다.
     */
    @NotBlank(message = "이메일이 입력되지 않았습니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email,

    /**
     * 표시용 이름. 비우면 이메일 앞부분을 쓴다.
     */
    String nickname

) {
    
}
