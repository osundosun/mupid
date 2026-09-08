package com.osundosun.mupid.api.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;
import com.osundosun.mupid.api.email.service.EmailService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.request.UserPasswordResetRequest;
import com.osundosun.mupid.api.user.request.UserSubmitPasswordResetRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;
import com.osundosun.mupid.api.user.response.UserPasswordResetResponse;
import com.osundosun.mupid.api.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/user/reset")
@RequiredArgsConstructor
public class UserPasswordResetController {

    private final UserService userService;
    private final EmailService emailService;

    @GetMapping
    public ResponseEntity<BaseResponse<UserPasswordResetResponse>> sendReseteMail(
        @Valid @RequestBody UserPasswordResetRequest request
    ) {
        User user = userService
                        .getUserByEmail(request.email())
                        .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        int verificationId = emailService.sendVerification(
            request.email(),
            EmailVerificationPurpose.PW_RESET,
            user
        );
        return ResponseEntity.ok(BaseResponse.of(
            "인증 메일이 발송되었습니다.",
            new UserPasswordResetResponse(verificationId)
        ));
    }

    @PostMapping
    public ResponseEntity<BaseResponse<Void>> submitResetCode(
        @Valid @RequestBody UserSubmitPasswordResetRequest request
    ) {
        emailService.validateToken(request.verificationId(), request.token());
        EmailVerification verification = emailService.getVerification(request.verificationId());
        User user = verification.getUser();
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        UserUpdateRequest updateDto = new UserUpdateRequest(request.password(), null, null, null);
        user = userService.updateUser(user.getUserId(), updateDto);
        return ResponseEntity.ok(
            BaseResponse.message("패스워드가 변경되었습니다.")
        );
    }
    
}
