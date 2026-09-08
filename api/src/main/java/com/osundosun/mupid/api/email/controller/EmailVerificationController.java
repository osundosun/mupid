package com.osundosun.mupid.api.email.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;
import com.osundosun.mupid.api.email.request.EmailAddressVerificationRequest;
import com.osundosun.mupid.api.email.request.EmailSubmitAddressVerificationRequest;
import com.osundosun.mupid.api.email.response.EmailAddressVerificationResponse;
import com.osundosun.mupid.api.email.response.EmailCheckAddressVerification;
import com.osundosun.mupid.api.email.service.EmailService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/mail/verification")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailService emailService;
    
    @GetMapping
    public ResponseEntity<BaseResponse<EmailAddressVerificationResponse>> sendAddressVerificationMail(
        @Valid @RequestBody EmailAddressVerificationRequest request
    ) {
        int verificationId = emailService.sendVerification(
            request.email(),
            EmailVerificationPurpose.SIGNUP,
            null
        );
        return ResponseEntity.ok(BaseResponse.of(
            "인증 메일이 발송되었습니다.",
            new EmailAddressVerificationResponse(verificationId)
        ));
    }

    @PostMapping
    public ResponseEntity<BaseResponse<Void>> submitAddressVerificationCode(
        @Valid @RequestBody EmailSubmitAddressVerificationRequest request
    ) {
        emailService.validateToken(request.verificationId(), request.token());
        return ResponseEntity.ok(
            BaseResponse.message("이메일 인증에 성공하였습니다.")
        );
    }

    @GetMapping("/{verificationId}")
    public ResponseEntity<BaseResponse<EmailCheckAddressVerification>> checkAddressVerification(
        @PathVariable int verificationId
    ) {
        boolean isValid = emailService.isValid(verificationId);
        return ResponseEntity.ok(BaseResponse.of(
            new EmailCheckAddressVerification(isValid)
        ));
    }

}
