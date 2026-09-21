package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(

    @Pattern(
        regexp = UserPasswordPolicy.PASSWORD_REGEXP,
        message = UserPasswordPolicy.PASSWORD_MESSAGE
    )
    String password,

    @Size(min = 2, max = 10, message = "닉네임은 2~10자 사이어야 합니다.")
    String nickname,

    @Min(value = 1900, message = "출생 연도는 최소 1900 이상이어야 합니다.")
    Integer birthYear,

    String gender

) {
    
}
