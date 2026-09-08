package com.osundosun.mupid.api.email.service;

import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;
import com.osundosun.mupid.api.user.entity.User;

public interface EmailService {

    int sendVerification(String emailAddr, EmailVerificationPurpose purpose, User user);

    void validateToken(int verificationId, String token);

    boolean isValid(int verificationId);

    EmailVerification getVerification(int verificationId);

}
