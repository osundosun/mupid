package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserSubmitPasswordResetRequest(

    int verificationId,

    @NotBlank(message = "토큰이 입력되지 않았습니다.")
    String token,

    @Pattern(
        // KISA 가이드라인 : 종류 2가지 이상 조합 시 10자 이상
        regexp = "^(?=(.*[A-Za-z].*[0-9])|(?=.*[A-Za-z].*[@$!%*#?&])|(?=.*[0-9].*[@$!%*#?&]))[A-Za-z\\d@$!%*#?&]{8,20}$",
        message = "비밀번호는 영문, 숫자, 특수문자 중 2가지 이상을 조합하여 8~20자로 입력해주세요."
    )
    @NotNull(message = "패스워드가 입력되지 않았습니다.")
    String password

) {
    
}
