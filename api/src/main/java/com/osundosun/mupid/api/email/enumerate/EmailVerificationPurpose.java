package com.osundosun.mupid.api.email.enumerate;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum EmailVerificationPurpose {

    SignUp("회원가입 이메일 인증", "이메일 인증 번호를 보내드립니다.", "mail/email-verification", "SIGNUP"),
    PasswordReset("패스워드 초기화 이메일 인증", "패스워드 초기화 인증 번호를 보내드립니다.", "mail/password-reset", "PW_RESET");

    private final String displayName;
    private final String mailTitle;
    private final String mailTemplate;
    private final String code;
    
    private static final Map<String, EmailVerificationPurpose> CODE_MAP = Collections.unmodifiableMap(
        Arrays.stream(values())
            .collect(Collectors.toMap(it -> it.getCode(), Function.identity()))
    );

    public static EmailVerificationPurpose fromCode(String code) {
        if (code == null) return null;
        EmailVerificationPurpose value = CODE_MAP.get(code);
        if (value == null)
            throw new IllegalArgumentException("Unknown code (%s)".formatted(code));
        return value;
    }
}
