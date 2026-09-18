package com.osundosun.mupid.api.email.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.gmail.GmailProvider;
import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;
import com.osundosun.mupid.api.email.error.EmailErrorCode;
import com.osundosun.mupid.api.email.repository.EmailVerificationRepository;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private static final int TOKEN_LENGTH = 6;
    private static final char[] RANDOM_CHAR_TABLE = {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
        'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J',
        'K', 'L', 'N', 'M', 'O', 'P', 'Q', 'R', 'S', 'T',
        'U', 'V', 'W', 'X', 'Y', 'Z'
    };

    /* frontend/public 의 메일용 로고 축소본. frontend/scripts/build-logo.mjs 가 만듭니다. */
    private static final String MAIL_LOGO_PATH = "/mupid-logo-email.png";

    public static final long EMAIL_VERIFICATION_EXPIRED_MINUTES = 15;
    public static final long EMAIL_VERIFICATION_VALID_EXPIRATION_HOURS = 1;

    private final GmailProvider gmailProvider;
    private final EmailTemplateService templateService;
    private final EmailVerificationRepository repository;

    @Value("${mupid-client.website}")
    private String mupidClientWebsite;

    @Override
    public int sendVerification(String emailAddr, EmailVerificationPurpose purpose, User user) {
        LocalDateTime now = LocalDateTime.now();
        String token = randomToken();

        EmailVerification verification = EmailVerification.builder()
            .user(user == null ? null : user)
            .email(emailAddr)
            .token(token)
            .purpose(purpose)
            .expiredAt(now.plusMinutes(EMAIL_VERIFICATION_EXPIRED_MINUTES))
            .build();
        verification = repository.save(verification);

        Map<String, Object> variables = new HashMap<>();
        variables.put("code", token);
        if (user != null) {
            variables.put("userName", user.getNickname());
        }
        if (!mupidClientWebsite.isBlank()) {
            /* 메일 헤더 로고. 웹사이트 주소가 없으면 템플릿이 글자 워드마크로 대체합니다. */
            variables.put("logoUrl", buildMailLogoUrl(mupidClientWebsite));
        }
        String mailBody = templateService.buildHtml(purpose.getMailTemplate(), variables);
        String subject = "[뮤피드] %s".formatted(purpose.getMailTitle());
        gmailProvider.sendEmail(emailAddr, subject, mailBody);
        return verification.getVerificationId();
    }

    @Override
    public void validateToken(int verificationId, String token) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = repository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(EmailErrorCode.INVALID_VERIFICATION_ID));
        if (verification.getVerifiedAt() != null) {
            throw new BusinessException(EmailErrorCode.ALREADY_VERIFIED_TOKEN);
        }
        if (verification.getExpiredAt().isBefore(now)) {
            throw new BusinessException(EmailErrorCode.VERIFICATION_HAS_EXPIRED);
        }
        if (!verification.getToken().equals(token)) {
            throw new BusinessException(EmailErrorCode.INVALID_VERIFICATION_TOKEN);
        }
        verification.setVerifiedAt(now);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValid(int verificationId) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = repository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(EmailErrorCode.INVALID_VERIFICATION_ID));
        LocalDateTime verifiedAt = verification.getVerifiedAt();

        return (
            verifiedAt != null &&

            /* 유효성 판단: 인증 완료 일시가 만료 일시 이전인지? */
            verification.getExpiredAt().isAfter(verifiedAt) &&

            /* 유효 기간: 인증된 시간으로 부터 너무 오랜 시간이 지나지 않았는지? */
            Duration.between(verifiedAt, now).toHours() < EMAIL_VERIFICATION_VALID_EXPIRATION_HOURS
        );
    }

    @Override
    public EmailVerification getVerification(int verificationId) {
       return repository.findById(verificationId).orElse(null);
    }

    private String randomToken() {
        char[] token_sequence = new char[TOKEN_LENGTH];
        ThreadLocalRandom localRandom = ThreadLocalRandom.current();
        for (int index = 0; index < TOKEN_LENGTH; index++) {
            int pos = localRandom.nextInt(0, 36);
            token_sequence[index] = RANDOM_CHAR_TABLE[pos];
        }
        return String.valueOf(token_sequence);
    }

    static String buildMailLogoUrl(String websiteUrl) {
        return UriComponentsBuilder.fromUriString(websiteUrl)
            .replacePath(MAIL_LOGO_PATH)
            .replaceQuery(null)
            .fragment(null)
            .encode()
            .toUriString();
    }

}
