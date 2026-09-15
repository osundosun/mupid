package com.osundosun.mupid.api.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.onboarding.error.OnboardingErrorCode;
import com.osundosun.mupid.api.onboarding.request.OnboardingSubmitRequest;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 온보딩은 **한 번만** 제출할 수 있다는 규칙의 검증.
 *
 * 예전에는 다시 제출하면 이전 응답을 통째로 지우고 새로 넣었다
 * (`knowledge/decision/0017`). 지금은 409 로 막는다 — 콜드스타트 입력이라
 * 고쳐 쓰는 값이 아니고, 조합으로 깔아 두는 저장 형태라 부분 수정도 성립하지
 * 않는다.
 *
 * 닉네임은 회원가입이 받으므로 제출이 `users.nickname` 을 건드리지 않는다는
 * 것도 함께 본다. 로컬 MySQL 이 떠 있어야 하고 트랜잭션은 매번 롤백된다.
 */
@SpringBootTest
@Transactional
class OnboardingSubmitOnceTest {

    @Autowired
    private OnboardingService onboardingService;

    @PersistenceContext
    private EntityManager em;

    private User user;
    private Code calm;
    private Code dreamy;
    private Code jazz;

    @BeforeEach
    void setUp() {
        user = persistUser();
        calm = persistCode(CodeType.MOOD, "잔잔한");
        dreamy = persistCode(CodeType.MOOD, "몽환적인");
        jazz = persistCode(CodeType.GENRE, "Jazz");
        em.flush();
    }

    @Test
    @DisplayName("처음 제출하면 고른 분위기 × 장르만큼 줄이 생긴다")
    void storesCrossProductOnFirstSubmit() {
        onboardingService.submit(user, request(List.of(calm.getCodeId(), dreamy.getCodeId()),
            List.of(jazz.getCodeId())));
        em.flush();

        assertTrue(onboardingService.hasSubmitted(user.getUserId()));
        assertEquals(2L, countAnswers());
    }

    @Test
    @DisplayName("두 번째 제출은 409 이고 이전 응답이 남는다")
    void rejectsSecondSubmit() {
        onboardingService.submit(user, request(List.of(calm.getCodeId()), List.of(jazz.getCodeId())));
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> onboardingService.submit(user,
                request(List.of(dreamy.getCodeId()), List.of(jazz.getCodeId()))));

        assertEquals(OnboardingErrorCode.ONBOARDING_ALREADY_SUBMITTED, e.getErrorCode());
        /* 갈아 끼우지 않았으므로 처음 넣은 한 줄 그대로다 */
        assertEquals(1L, countAnswers());
    }

    @Test
    @DisplayName("제출은 계정 닉네임을 건드리지 않는다")
    void keepsAccountNickname() {
        String before = user.getNickname();

        onboardingService.submit(user, request(List.of(calm.getCodeId()), List.of(jazz.getCodeId())));
        em.flush();

        assertEquals(before, user.getNickname());
    }

    // ─────────────────────────────────────────────────────────────

    private OnboardingSubmitRequest request(List<Integer> moodIds, List<Integer> genreIds) {
        return new OnboardingSubmitRequest(moodIds, genreIds);
    }

    private long countAnswers() {
        return em.createQuery(
                "SELECT COUNT(s) FROM OnboardingSurvey s WHERE s.user = :user", Long.class)
            .setParameter("user", user)
            .getSingleResult();
    }

    private User persistUser() {
        User newUser = User.builder()
            /* 이메일이 유니크라 테스트끼리 부딪히지 않게 매번 새로 만든다 */
            .email("test-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(newUser);
        return newUser;
    }

    private Code persistCode(CodeType type, String name) {
        Code code = Code.builder()
            .codeType(type)
            /* `codes` 는 (code_type, code_name) 이 유니크다. 부트스트랩이 채운 기준
             * 데이터와 부딪히지 않게 꼬리를 붙인다 — `OnboardingResultTest` 와 같다. */
            .codeName("%s-%s".formatted(name, UUID.randomUUID().toString().substring(0, 8)))
            .build();
        em.persist(code);
        return code;
    }

}
