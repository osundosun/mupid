package com.osundosun.mupid.api.email.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public record EmailAddressVerificationRequest(

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @NotNull(message = "이메일이 입력되지 않았습니다.")
    String email

) {
    
}
