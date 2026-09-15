package com.osundosun.mupid.api.utils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PasswordGenerateUtils {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL = "!@#$%^&*()-_=+[]{}|;:,.<>?";
    private static final String ALL_CHARS = UPPER + LOWER + DIGITS + SPECIAL;

    // SecureRandom 인스턴스 생성 (암호학적으로 안전함)
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generatePassword(int length) {
        if (length < 8) {
            throw new IllegalArgumentException("패스워드는 최소 8자 이상이어야 합니다.");
        }

        List<Character> passwordChars = new ArrayList<>();

        // 1. 보안 정책 충족을 위해 각 카테고리에서 최소 1글자씩 강제 추출
        passwordChars.add(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        passwordChars.add(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        passwordChars.add(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        passwordChars.add(SPECIAL.charAt(RANDOM.nextInt(SPECIAL.length())));

        // 2. 나머지 자릿수는 전체 문자 집합에서 무작위 선택
        for (int i = 4; i < length; i++) {
            passwordChars.add(ALL_CHARS.charAt(RANDOM.nextInt(ALL_CHARS.length())));
        }

        // 3. 앞 4자리에 고정된 문자 순서를 무작위로 섞음 (Fisher-Yates 셔플)
        Collections.shuffle(passwordChars, RANDOM);

        // 4. 문자열로 조합
        StringBuilder sb = new StringBuilder(length);
        for (char ch : passwordChars) {
            sb.append(ch);
        }

        return sb.toString();
    }
    
}
