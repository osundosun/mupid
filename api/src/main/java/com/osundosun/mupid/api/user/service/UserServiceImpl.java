package com.osundosun.mupid.api.user.service;

import java.time.Year;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.utils.PasswordGenerateUtils;
import com.osundosun.mupid.api.email.entity.EmailVerification;
import com.osundosun.mupid.api.email.service.EmailService;
import com.osundosun.mupid.api.user.dto.SystemUserCreateResult;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.repository.UserRepository;
import com.osundosun.mupid.api.user.request.SystemUserCreateRequest;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /**
     * 배치 계정 비밀번호 길이. 사람이 외울 일이 없으므로 길게 잡는다.
     */
    public static final int SYSTEM_USER_PASSWORD_LENGTH = 32;

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
            .role(UserRole.ROLE_USER)
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
    public SystemUserCreateResult createSystemUser(SystemUserCreateRequest dto) {
        if (getUserByEmail(dto.email()).isPresent()) {
            throw new BusinessException(UserErrorCode.DUPLICATE_EMAIL);
        }

        // 사람이 로그인할 계정이 아니라서 비밀번호를 받지 않고 서버가 만든다.
        String password = PasswordGenerateUtils.generatePassword(SYSTEM_USER_PASSWORD_LENGTH);
        String nickname = (dto.nickname() == null || dto.nickname().isBlank())
            ? dto.email().split("@")[0]
            : dto.nickname();

        User newUser = repository.save(User.builder()
            .email(dto.email())
            .password(passwordEncoder.encode(password))
            .nickname(nickname)
            .birthYear(Year.now().getValue())
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_SYSTEM)
            .status(UserStatus.ACTIVE) /* 온보딩 대상이 아니다 */
            .build());

        return new SystemUserCreateResult(newUser, password);
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
    public Optional<User> getUserByImageFileId(int imageFileId) {
        return repository.findByImageFileId(imageFileId);
    }

    @Override
    public User updateUser(int userId, UserUpdateRequest dto) {
        User user = getUser(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (dto.password() != null) user.setPassword(passwordEncoder.encode(dto.password()));
        if (dto.nickname() != null) {
            // 앞뒤 공백은 이름의 일부가 아니다. 온보딩 제출도 같게 다룬다.
            String nickname = dto.nickname().trim();
            if (nickname.isEmpty()) throw new BusinessException(UserErrorCode.INVALID_NICKNAME);
            user.setNickname(nickname);
        }
        if (dto.birthYear() != null) user.setBirthYear(dto.birthYear());
        if (dto.gender() != null) user.setGender(UserGender.valueOf(dto.gender()));
        return user;
    }

    @Override
    public User activateUser(int userId) {
        User user = getUser(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        /*
         * `PENDING` 일 때만 올린다. `ACTIVE` 를 다시 쓰면 `updatedAt` 만 흔들리고,
         * 탈퇴 계정은 `getUser` 가 애초에 내주지 않는다.
         */
        if (user.getStatus() == UserStatus.PENDING) {
            user.setStatus(UserStatus.ACTIVE);
        }
        return user;
    }

    @Override
    public void deleteUser(int userId) {
        User user = getUser(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        repository.delete(user);
    }

    @Override
    public boolean isAdminUserExists() {
        return repository.existsByRole(UserRole.ROLE_ADMIN);
    }
    
}