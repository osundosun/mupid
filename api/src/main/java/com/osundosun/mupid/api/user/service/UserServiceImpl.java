package com.osundosun.mupid.api.user.service;

import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.service.EmailService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.repository.UserRepository;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserRepository repository;
    private final EmailService emailService;

    @Override
    public User createUser(UserCreateRequest dto) {
        User newUser = User.builder()
            .email(dto.email())
            .password(passwordEncoder.encode(dto.password()))
            .nickname(dto.nickname())
            .birthYear(dto.birthYear())
            .gender(UserGender.valueOf(dto.gender()))
            .status(UserStatus.PENDING) /* 온보딩 필요함 */
            .role(UserRole.USER)
            .build();
        
        // 이메일 인증 확인
        EmailVerification emailVerification = emailService.getVerification(dto.emailVerificationId());
        if (!emailService.isValid(dto.emailVerificationId()) || !emailVerification.getEmail().equals(dto.email())) {
            throw new BusinessException(UserErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }
        // 이메일 중복 체크
        if (getUserByEmail(dto.email()).isPresent()) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }

        return repository.save(newUser);
    }

    @Override
    public Optional<User> getUser(int userId) {
        return repository.findByUserIdAndStatusNot(userId, UserStatus.WITHDRAW);
    }
    
    @Override
    public Optional<User> getUserByEmail(String email) {
        return repository.findByEmailAndStatusNot(email, UserStatus.WITHDRAW);
    }

    @Override
    public User updateUser(int userId, UserUpdateRequest dto) {
        User user = getUser(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (dto.password() != null) user.setPassword(passwordEncoder.encode(dto.password()));
        if (dto.nickname() != null) user.setNickname(dto.nickname());
        if (dto.birthYear() != null) user.setBirthYear(dto.birthYear());
        if (dto.gender() != null) user.setGender(UserGender.valueOf(dto.gender()));
        return user;
    }

    @Override
    public void deleteUser(int userId) {
        User user = getUser(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        repository.delete(user);
    }
    
}