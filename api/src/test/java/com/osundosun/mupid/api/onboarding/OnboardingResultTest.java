package com.osundosun.mupid.api.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.entity.OnboardingSurvey;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 사용자 번호로 읽는 온보딩 결과(`GET /api/v1/user/{userId}/onboarding`)의 서비스 계층 검증.
 *
 * 핵심은 **"아직 제출 안 함" 과 "없는 사용자" 가 갈리는지**다. 배치에게 앞은
 * 건너뛸 일, 뒤는 데이터가 어긋난 일이라 같은 응답으로 뭉개면 안 된다.
 *
 * 저장은 분위기 × 장르 조합이므로 **응답이 조합을 드러내지 않고 고른 집합
 * 그대로 돌아오는지**도 함께 본다 (`knowledge/decision/0017`).
 *
 * 권한(`@SystemRole`)은 컨트롤러 애너테이션이 막으므로 여기서 다루지 않는다.
 * 로컬 MySQL 이 떠 있어야 하고, 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class OnboardingResultTest {

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
    @DisplayName("제출한 적이 없으면 빈 결과이고 오류가 아니다")
    void returnsEmptyResultWhenNotSubmitted() {
        OnboardingResultResponse result = onboardingService.getResult(user.getUserId());

        assertEquals(user.getUserId(), result.userId());
        assertFalse(result.submitted());
        assertNull(result.submittedAt());
        assertTrue(result.moods().isEmpty());
        assertTrue(result.genres().isEmpty());
    }

    @Test
    @DisplayName("없는 사용자는 빈 결과가 아니라 404 다")
    void rejectsMissingUser() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> onboardingService.getResult(-1));

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("탈퇴한 사용자는 없는 것으로 본다")
    void rejectsWithdrawnUser() {
        user.setStatus(UserStatus.WITHDRAW);
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> onboardingService.getResult(user.getUserId()));

        assertEquals(UserErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("조합으로 저장돼 있어도 고른 집합 그대로 돌아온다")
    void restoresChosenSetFromCrossProduct() {
        /* 분위기 2 × 장르 1 = 2줄. 응답은 분위기 2개·장르 1개여야 한다. */
        persistAnswer(calm, jazz);
        persistAnswer(dreamy, jazz);
        em.flush();

        OnboardingResultResponse result = onboardingService.getResult(user.getUserId());

        assertTrue(result.submitted());
        assertNotNull(result.submittedAt());
        assertEquals(List.of(calm.getCodeId(), dreamy.getCodeId()), codeIds(result.moods()));
        assertEquals(List.of(jazz.getCodeId()), codeIds(result.genres()));
    }

    // ─────────────────────────────────────────────────────────────

    private List<Integer> codeIds(List<CodeOption> options) {
        return options.stream().map(CodeOption::codeId).toList();
    }

    private void persistAnswer(Code mood, Code genre) {
        em.persist(OnboardingSurvey.builder()
            .user(user)
            .moodCode(mood)
            .genreCode(genre)
            .build());
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
            /*
             * `codes` 는 (code_type, code_name) 이 유니크다 (`ix_codes_type_name`).
             * 기동 때 `BootstrapRunner` 가 기준 데이터를 채우므로 "잔잔한" 을 그대로
             * 넣으면 부딪힌다. 검증에 쓰는 것은 이름이 아니라 번호라 꼬리를 붙인다.
             * `code_name` 이 VARCHAR(50) 이라 UUID 를 통째로 붙이지는 않는다.
             */
            .codeName("%s-%s".formatted(name, UUID.randomUUID().toString().substring(0, 8)))
            .build();
        em.persist(code);
        return code;
    }

}
