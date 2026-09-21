package com.osundosun.mupid.api.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.auth.provider.AuthProvider;
import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.auth.service.AuthService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.history.entity.SearchHistory;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;
import com.osundosun.mupid.api.user.service.UserService;
import com.osundosun.mupid.api.user.service.UserServiceImpl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 회원 탈퇴가 소프트삭제인지 검증한다. 트랜잭션은 테스트마다 롤백된다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * 한때 {@code UserServiceImpl.deleteUser} 가 {@code repository.delete(user)} 로 행을
 * 지웠다. 조회 쪽은 이미 {@code UserStatus.WITHDRAW} 를 제외하고 있었으므로
 * <b>같은 코드 안에서 두 정책이 충돌하는 상태</b>였다 —
 * {@code knowledge/decision/0013-soft-delete-policy.md}.
 *
 * <p>여기서 못박는 것은 셋이다.
 *
 * <ol>
 *   <li><b>행이 남는다.</b> 그리고 그 행에 매달린 자식 행(예: 최근 검색어)도 함께
 *       사라지지 않는다. FK 가 {@code NO ACTION} 인 테이블이 대부분이라 하드 삭제는
 *       애초에 실패했고, {@code ai_chat_sessions} 처럼 {@code CASCADE} 인 곳만
 *       조용히 딸려 나갔다.</li>
 *   <li><b>탈퇴 뒤 그 계정으로는 못 들어온다.</b> 기존
 *       {@code findByUserIdAndStatusNot}·{@code findByEmailAndStatusNot} 경로가 실제로
 *       입구 셋(로그인·기존 토큰 인증·토큰 재발급)을 막는지 각각 확인한다. 조회
 *       서비스({@code getUser})가 막는 것만으로는 부족하다 — 토큰 경로는 서비스를
 *       거치지 않고 리포지토리를 직접 부른다.</li>
 *   <li><b>자격증명은 남기지 않는다.</b> 행을 남기기로 한 대가로 비밀번호가 DB 에
 *       남는데, 그 값으로는 인증되지 않아야 한다.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserSoftDeleteTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthProvider authProvider;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    /** 탈퇴 대상의 원래 비밀번호. 무력화 뒤에도 이 값으로는 못 들어와야 한다. */
    private static final String RAW_PASSWORD = "Passw0rd!";

    private User me;

    @BeforeEach
    void setUp() {
        me = User.builder()
            .email("test-%s@mupid.test".formatted(UUID.randomUUID()))
            .password(passwordEncoder.encode(RAW_PASSWORD))
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(me);
        em.flush();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 행이 남는다

    @Test
    @DisplayName("회원탈퇴는 행을 지우지 않고 상태만 WITHDRAW 로 옮긴다")
    void softDeletesInsteadOfDeletingRow() {
        int userId = me.getUserId();

        userService.deleteUser(userId);
        em.flush();

        assertEquals(UserStatus.WITHDRAW, me.getStatus());
        /* 1차 캐시가 아니라 DB 에 물어본다 — 진짜로 행이 남았는지 */
        assertEquals(1L, countUserRows(userId), "탈퇴해도 users 행은 남아야 한다");
    }

    @Test
    @DisplayName("탈퇴해도 자식 행은 딸려 지워지지 않는다")
    void keepsChildRows() {
        em.persist(SearchHistory.builder().user(me).keyword("무피드").build());
        em.flush();

        userService.deleteUser(me.getUserId());
        em.flush();
        em.clear();

        assertEquals(1L, em.createQuery(
                "SELECT COUNT(h) FROM SearchHistory h WHERE h.user.userId = :userId", Long.class)
            .setParameter("userId", me.getUserId())
            .getSingleResult());
    }

    @Test
    @DisplayName("이미 탈퇴한 계정을 또 탈퇴시키면 404 가 아니라 410 이다")
    void rejectsDoubleDelete() {
        userService.deleteUser(me.getUserId());
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> userService.deleteUser(me.getUserId()));

        /*
         * 예전에는 404 였다. 탈퇴는 행을 지우지 않으므로 "그런 회원이 없다" 와
         * "탈퇴했다" 를 나눌 수 있고, 보낸 쪽이 할 일이 다르다 — 앞은 번호를
         * 고쳐야 하고, 뒤는 그 계정을 포기해야 한다.
         */
        assertEquals(UserErrorCode.USER_GONE, e.getErrorCode());
        assertEquals(HttpStatus.GONE, UserErrorCode.USER_GONE.getStatus());
    }

    @Test
    @DisplayName("탈퇴한 회원을 수정하려 하면 410 이다 — 없는 회원과 구분된다")
    void goneIsDistinctFromNotFoundOnUpdate() {
        int withdrawn = me.getUserId();
        userService.deleteUser(withdrawn);
        em.flush();

        BusinessException gone = assertThrows(BusinessException.class,
            () -> userService.updateUser(withdrawn, new UserUpdateRequest(null, "새이름", null, null)));
        assertEquals(UserErrorCode.USER_GONE, gone.getErrorCode());

        /* 애초에 없는 번호는 그대로 404 다 — 이 구분이 이 테스트의 요점이다. */
        BusinessException missing = assertThrows(BusinessException.class,
            () -> userService.updateUser(Integer.MAX_VALUE,
                new UserUpdateRequest(null, "새이름", null, null)));
        assertEquals(UserErrorCode.USER_NOT_FOUND, missing.getErrorCode());
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 탈퇴 뒤에는 들어올 수 없다

    @Test
    @DisplayName("탈퇴하면 getUser 로 조회되지 않는다")
    void hidesWithdrawnUserFromLookup() {
        userService.deleteUser(me.getUserId());
        em.flush();

        assertTrue(userService.getUser(me.getUserId()).isEmpty());
    }

    @Test
    @DisplayName("탈퇴하면 같은 이메일로 로그인할 수 없다")
    void blocksLoginAfterWithdrawal() {
        String email = me.getEmail();
        assertEquals(me.getUserId(), authService.login(email, RAW_PASSWORD).user().getUserId());

        userService.deleteUser(me.getUserId());
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> authService.login(email, RAW_PASSWORD));

        /*
         * 미가입 이메일과 같은 코드다 (AuthServiceImpl).
         * "탈퇴한 계정"이라고 알려 주면 계정 존재 여부가 드러난다.
         */
        assertEquals(AuthErrorCode.WRONG_EMAIL_OR_PASSWORD, e.getErrorCode());
    }

    @Test
    @DisplayName("탈퇴 전에 발급받은 액세스 토큰도 통하지 않는다")
    void blocksExistingAccessToken() {
        String accessToken = jwtProvider.issueAccessToken(me);
        assertEquals(me.getUserId(), ((User)authProvider.auth(accessToken).getPrincipal()).getUserId());

        userService.deleteUser(me.getUserId());
        em.flush();

        /*
         * 필터가 부르는 그 자리다 (`AuthProvider.auth`). 토큰은 아직 서명도 만료도
         * 멀쩡하지만, 주체를 DB 에서 다시 읽을 때 탈퇴 계정이 걸러진다 —
         * 탈퇴 직후 1시간짜리 액세스 토큰이 살아 있는 창을 여기서 닫는다.
         */
        assertThrows(BadCredentialsException.class, () -> authProvider.auth(accessToken));
    }

    @Test
    @DisplayName("탈퇴 전에 발급받은 리프레시 토큰으로도 재발급할 수 없다")
    void blocksExistingRefreshToken() {
        String refreshToken = jwtProvider.issueRefreshToken(me);

        userService.deleteUser(me.getUserId());
        em.flush();

        assertThrows(BadCredentialsException.class, () -> authProvider.refresh(refreshToken));
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 자격증명은 남기지 않는다

    @Test
    @DisplayName("탈퇴하면 비밀번호가 무력화된다")
    void neutralizesPassword() {
        userService.deleteUser(me.getUserId());
        em.flush();

        assertEquals(UserServiceImpl.WITHDRAWN_PASSWORD_PLACEHOLDER, me.getPassword());
        /* 원래 비밀번호로도, 남긴 자리표시자로도 인증되지 않는다 */
        assertFalse(passwordEncoder.matches(RAW_PASSWORD, me.getPassword()));
        assertFalse(passwordEncoder.matches(UserServiceImpl.WITHDRAWN_PASSWORD_PLACEHOLDER, me.getPassword()));
    }

    // ─────────────────────────────────────────────────────────────
    // 4. HTTP 경로

    @Test
    @DisplayName("DELETE /api/v1/user 는 응답 문구를 그대로 두고 탈퇴 처리한다")
    void servesDeleteOverHttp() throws Exception {
        mockMvc.perform(delete("/api/v1/user")
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("회원탈퇴 처리 되었습니다."));

        assertEquals(UserStatus.WITHDRAW, me.getStatus());
        assertEquals(1L, countUserRows(me.getUserId()));
    }

    // ─────────────────────────────────────────────────────────────

    /** 탈퇴 여부와 무관하게 테이블에 그 행이 있는가. 상태 필터를 걸지 않는다. */
    private long countUserRows(int userId) {
        return em.createQuery("SELECT COUNT(u) FROM User u WHERE u.userId = :userId", Long.class)
            .setParameter("userId", userId)
            .getSingleResult();
    }

}
