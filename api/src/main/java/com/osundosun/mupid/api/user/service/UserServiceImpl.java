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

    /**
     * 닉네임 상한. {@code users.nickname} 이 VARCHAR(10) 이다.
     */
    public static final int SYSTEM_USER_NICKNAME_MAX_LENGTH = 10;

    /**
     * 탈퇴한 계정의 `password` 자리에 남기는 값.
     *
     * <p>BCrypt 해시가 아니라 **해시로도 읽히지 않는 문자열**이다. 탈퇴 계정은
     * `findByEmailAndStatusNot(email, WITHDRAW)` 가 로그인 입구에서 이미 걸러내므로
     * 이 값이 인증에 쓰일 일은 없다. 그런데도 유효한 해시(`$2a$10$…`)를 남겨 두면
     * 언젠가 이메일 중복 검사나 조회 경로가 하나 풀리는 순간 그 계정이 다시
     * 로그인 가능해진다 — 자격증명은 인증되지 않는 자리에 남겨 두지 않는다.
     *
     * <p>평문이 아닌 고정 문자열인 것은 의도다. 무작위 값을 넣으면 "원래 비밀번호가
     * 무엇이었는지" 를 되돌릴 여지가 남지만, 애초에 되돌릴 것이 없어야 한다.
     */
    public static final String WITHDRAWN_PASSWORD_PLACEHOLDER = "WITHDRAWN";

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
        // 이메일 앞부분을 그대로 쓰면 `system-preprocessing` 처럼 쓰는 곳을 밝힌 이름이
        // users.nickname(VARCHAR(10))에 들어가지 않는다. 계정은 쓰는 곳마다 따로 둔다
        // (저장소 재설계 원칙 6) — 이름이 길어지는 것이 정상이라 여기서 자른다.
        String nickname = (dto.nickname() == null || dto.nickname().isBlank())
            ? abbreviate(dto.email().split("@")[0], SYSTEM_USER_NICKNAME_MAX_LENGTH)
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

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
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

    /**
     * 탈퇴한 회원이면 410, 그런 회원이 없으면 404 다.
     *
     * <p>탈퇴는 `status` 를 `WITHDRAW` 로 옮기는 소프트 삭제라 행이 남는다
     * (`deleteUser`). 조회(`getUser`)가 `StatusNot(WITHDRAW)` 로 걸러서 그대로는
     * 둘 다 비어 있으므로, 행 자체를 한 번 더 본다.
     */
    private User requireUser(int userId) {
        return getUser(userId).orElseGet(() -> {
            if (repository.findByUserId(userId).isPresent()) {
                throw new BusinessException(UserErrorCode.USER_GONE);
            }
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        });
    }

    @Override
    public User updateUser(int userId, UserUpdateRequest dto) {
        User user = requireUser(userId);
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
        User user = requireUser(userId);
        user.setStatus(UserStatus.WITHDRAW);
        /*
         * 행은 남기고 **자격증명은 버린다** — 무력화 판단의 근거.
         *
         * 남기는 쪽 근거: `users.user_id` 를 참조하는 테이블이 11개인데 그중 10개가
         * `NO ACTION` 이라 하드 삭제는 FK 에 막혀 실패하고, 유일하게 `CASCADE` 인
         * `ai_chat_sessions` 는 조용히 함께 지워진다. 어느 쪽도 의도가 아니고, 탈퇴
         * 뒤에도 `user_id` 참조가 살아 있어야 청취 이벤트 등 분석 데이터가 고아가 되지
         * 않는다 (knowledge/decision/0013-soft-delete-policy.md).
         *
         * 버리는 쪽 근거: 그 결정 문서도 "soft delete 는 파기가 아니다. 개인정보 파기
         * 요구가 있으면 별도 처리가 필요하다" 라고 적어 두었다. 그 별도 처리가 여기다.
         * `password` 는 프로필 값이 아니라 **자격증명**이고, 탈퇴한 계정이 인증할 일은
         * 없다. 보존할 이유가 없는 값을 DB 유출 시 오프라인 대입 공격의 재료로 남겨 둘
         * 이유도 없다 — 비밀번호만 무력화하고 이메일·프로필은 분석 참조를 위해 남긴다.
         * 남길 값(이메일·닉네임·출생연도·성별)과 버릴 값(비밀번호)을 가르는 기준은
         * "그 값이 인증에 쓰이는가" 하나다.
         *
         * 닉네임 등 프로필 값을 함께 지우지 않는 것은, 그 값이 조회 경로
         * (`findByUserIdAndStatusNot`)에서 이미 전부 빠지고 어떤 FK·분석에도 남지 않아
         * 이 티켓에서 다룰 대상이 아니기 때문이다. 탈퇴 행을 어떤 모습으로 남길지는
         * 팀 합의가 필요한 별도 사안이다.
         *
         * ⚠ `users.password` 의 ERD 컬럼 주석은 "암호화된 비밀번호(BCrypt)" 인데
         * `WITHDRAWN_PASSWORD_PLACEHOLDER` 는 해시가 아니다. 컬럼 주석을 고치려면 DDL
         * (ERD 재산출)이 필요해 이 티켓 범위 밖이므로, 어긋난 사실을 여기와 커밋
         * 메시지에 남긴다. 이 행의 권위 있는 신호는 `status` 다.
         */
        user.setPassword(WITHDRAWN_PASSWORD_PLACEHOLDER);
    }

    @Override
    public boolean isAdminUserExists() {
        return repository.existsByRole(UserRole.ROLE_ADMIN);
    }
    
}