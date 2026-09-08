package com.osundosun.mupid.api.user.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserUpdateRequest(

    @Pattern(
        // KISA 가이드라인 : 종류 2가지 이상 조합 시 10자 이상
        regexp = "^(?=(.*[A-Za-z].*[0-9])|(?=.*[A-Za-z].*[@$!%*#?&])|(?=.*[0-9].*[@$!%*#?&]))[A-Za-z\\d@$!%*#?&]{8,20}$",
        message = "비밀번호는 영문, 숫자, 특수문자 중 2가지 이상을 조합하여 8~20자로 입력해주세요."
    )
    String password,

    @Size(min = 2, max = 10, message = "닉네임은 2~10자 사이어야 합니다.")
    String nickname,

    @Min(value = 1900, message = "출생 연도는 최소 1900 이상이어야 합니다.")
    Integer birthYear,

    String gender

) {
    
}
