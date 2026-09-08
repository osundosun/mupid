package com.osundosun.mupid.api.email.request;

import jakarta.validation.constraints.NotBlank;

public record EmailSubmitAddressVerificationRequest(

    int verificationId,

    @NotBlank(message = "토큰이 입력되지 않았습니다.")
    String token

) {
    
}
