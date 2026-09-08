package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(

    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @NotNull(message = "이메일이 입력되지 않았습니다.")
    String email,

    @NotNull(message = "이메일 인증이 필요합니다.")
    Integer emailVerificationId,

    @Pattern(
        // KISA 가이드라인 : 종류 2가지 이상 조합 시 10자 이상
        regexp = "^(?=(.*[A-Za-z].*[0-9])|(?=.*[A-Za-z].*[@$!%*#?&])|(?=.*[0-9].*[@$!%*#?&]))[A-Za-z\\d@$!%*#?&]{8,20}$",
        message = "비밀번호는 영문, 숫자, 특수문자 중 2가지 이상을 조합하여 8~20자로 입력해주세요."
    )
    @NotNull(message = "패스워드가 입력되지 않았습니다.")
    String password,

    @Size(min = 2, max = 10, message = "닉네임은 2~10자 사이어야 합니다.")
    @NotBlank(message = "닉네임이 입력되지 않았습니다.")
    String nickname,

    @Min(value = 1900, message = "출생 연도는 최소 1900 이상이어야 합니다.")
    @NotNull(message = "출생 연도가 입력되지 않았습니다.")
    Integer birthYear,

    @NotBlank(message = "성별이 입력되지 않았습니다.")
    String gender

) {
    
}
