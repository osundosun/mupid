package com.osundosun.mupid.api.user.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.service.EmailService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
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
            .gender(UserGender.fromCode(dto.gender()))
            .status(UserStatus.Pending) /* 온보딩 필요함 */
            .build();
        
        // 이메일 인증 확인
        EmailVerification emailVerification = emailService.getVerification(dto.emailVerificationId());
        if (!emailService.isValid(dto.emailVerificationId()) || !emailVerification.getEmail().equals(dto.email())) {
            throw new BusinessException(UserErrorCode.EmailVerificationRequired);
        }

        return repository.save(newUser);
    }

    @Override
    public User getUserOrThrow(int userId) {
        User user = repository.findById(userId).orElse(null);
        if (user == null || user.getStatus().equals(UserStatus.Withdraw)) {
            throw new BusinessException(UserErrorCode.UserNotFound);
        }
        return user;
    }
    
    @Override
    public User getUserByEmail(String email) {
        User user = repository.findByEmail(email);
        if (user == null || user.getStatus().equals(UserStatus.Withdraw)) {
            return null;
        }
        return user;
    }

    @Override
    public User updateUser(int userId, UserUpdateRequest dto) {
        User user = getUserOrThrow(userId);
        if (dto.password() != null) user.setPassword(dto.password());
        if (dto.nickname() != null) user.setNickname(dto.nickname());
        if (dto.birthYear() != null) user.setBirthYear(dto.birthYear());
        if (dto.gender() != null) user.setGender(UserGender.fromCode(dto.gender()));
        return user;
    }

    @Override
    public void deleteUser(int userId) {
        User user = getUserOrThrow(userId);
        repository.delete(user);
    }
    
}