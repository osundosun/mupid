package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UserSubmitPasswordResetRequest(

    int verificationId,

    @NotBlank(message = "토큰이 입력되지 않았습니다.")
    String token,

    @Pattern(
        regexp = UserPasswordPolicy.PASSWORD_REGEXP,
        message = UserPasswordPolicy.PASSWORD_MESSAGE
    )
    @NotNull(message = "패스워드가 입력되지 않았습니다.")
    String password

) {
    
}
