package com.osundosun.mupid.api.email.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EmailVerificationPurpose {

    SIGNUP("회원가입 이메일 인증", "이메일 인증 번호를 보내드립니다.", "mail/email-verification", "/verify/email"),
    PW_RESET("패스워드 초기화 이메일 인증", "패스워드 초기화 인증 번호를 보내드립니다.", "mail/password-reset", "/verify/password");

    private final String displayName;
    private final String mailTitle;
    private final String mailTemplate;
    private final String mailUrl;

}
