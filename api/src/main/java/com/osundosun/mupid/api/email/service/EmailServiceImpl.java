package com.osundosun.mupid.api.email.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public static final long EMAIL_VERIFICATION_EXPIRED_MINUTES = 15;

    private final GmailProvider gmailProvider;
    private final EmailTemplateService templateService;
    private final EmailVerificationRepository repository;

    @Override
    public int sendVerification(String emailAddr, EmailVerificationPurpose purpose, User user) {
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();

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
        String mailBody = templateService.buildHtml(purpose.getMailTemplate(), variables);
        String subject = "[뮤피드] %s".formatted(purpose.getMailTitle());
        gmailProvider.sendEmail(emailAddr, subject, mailBody);

        return verification.getVerificationId();
    }

    @Override
    public void validateToken(int verificationId, String token) {
        LocalDateTime now = LocalDateTime.now();
        EmailVerification verification = repository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(EmailErrorCode.VerificationIdNotFound));
        if (verification.getExpiredAt().isBefore(now)) {
            throw new BusinessException(EmailErrorCode.VerificationHasExpired);
        }
        if (!verification.getToken().equals(token)) {
            throw new BusinessException(EmailErrorCode.VerificationInvalidToken);
        }
        verification.setVerifiedAt(now);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isValid(int verificationId) {
        EmailVerification verification = repository.findById(verificationId)
                .orElseThrow(() -> new BusinessException(EmailErrorCode.VerificationIdNotFound));
        LocalDateTime verifiedAt = verification.getVerifiedAt();
        return verifiedAt != null && verification.getExpiredAt().isAfter(verifiedAt);
    }

    @Override
    public EmailVerification getVerification(int verificationId) {
       return repository.findById(verificationId).orElse(null);
    }

}
