package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record UserPasswordResetRequest(

    @Email(message = "이메일 형식이 잘못되었습니다.")
    @NotNull(message = "이메일이 입력되지 않았습니다.")
    String email

) {
    
}
