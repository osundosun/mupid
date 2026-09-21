package com.osundosun.mupid.api.inquiry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.inquiry.error.InquiryErrorCode;
import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.inquiry.service.InquiryNotifier;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * `POST /api/v1/inquiries` 의 HTTP 계약을 못박는다.
 *
 * <h2>계약의 출처는 프런트다</h2>
 *
 * `frontend/lib/api/endpoints.ts` 의 `inquiry.send` 가 정했다 —
 * {@code POST /api/v1/inquiries}, 본문 {@code {content}}, {@code responseType: 'none'}
 * (응답 본문 없음), 액세스 토큰 필요. 이 테스트는 그 네 가지를 서버 쪽에서 확인한다.
 *
 * <h2>목으로 두는 것은 알림뿐이다</h2>
 *
 * {@code InquiryNotifier} 만 {@code @MockitoBean} 이다. 서비스·컨트롤러·검증·오류 응답은
 * <b>실물</b>이 돈다 — 그래야 검증 문구와 상태 코드가 실제로 그렇게 나가는지 확인된다.
 * 알림을 목으로 두는 이유는 두 가지다. Gmail·Mattermost 없이 돌게 하려는 것, 그리고
 * {@code @Async} 를 무력화해 <b>요청 스레드에서 호출 여부를 검증</b>하려는 것.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InquiryContractTest {

    private static final String PATH = "/api/v1/inquiries";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @PersistenceContext
    private EntityManager em;

    @MockitoBean
    private InquiryNotifier notifier;

    private User me;

    @BeforeEach
    void setUp() {
        me = User.builder()
            .email("inquiry-%s@mupid.test".formatted(UUID.randomUUID()))
            .password(passwordEncoder.encode("Passw0rd!"))
            .nickname("문의자")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(me);
        em.flush();
    }

    private UsernamePasswordAuthenticationToken asMe() {
        return new UsernamePasswordAuthenticationToken(me, null, List.of());
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 성공 계약 — 경로·본문·응답

    @Test
    @DisplayName("200 이고 응답 본문이 없다 — responseType: 'none' 계약")
    void succeedsWithEmptyBody() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"로그인이 자꾸 풀려요\"}"))
            .andExpect(status().isOk())
            /*
             * 본문을 만들면 계약과 어긋난다. 프런트는 `responseType: 'none'` 이라 본문을
             * 읽지 않으므로 여기서 BaseResponse 를 만들면 서버만 규약을 어기게 된다.
             */
            .andExpect(content().string(""));

        verify(notifier).notifyInquiry("로그인이 자꾸 풀려요", me.getEmail(), me.getNickname());
    }

    @Test
    @DisplayName("경로·메서드가 endpoints.ts 와 같다 — 다른 경로는 404 다")
    void pathMatchesFrontendContract() throws Exception {
        /* 오타 하나로 조용히 다른 곳에 붙지 않았는지 본다. */
        mockMvc.perform(post("/api/v1/inquiry")
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"내용\"}"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("서버가 trim 해서 넘긴다 — 클라이언트가 보낸 공백에 기대지 않는다")
    void trimsContentBeforeNotifying() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   내용   \"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(notifier).notifyInquiry(content.capture(), anyString(), anyString());
        assertEquals("내용", content.getValue());
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 클라이언트가 보낸 이메일은 쓰이지 않는다

    @Test
    @DisplayName("본문에 email 을 끼워 넣어도 무시된다 — 받을 자리가 없다")
    void ignoresClientSuppliedEmail() throws Exception {
        /*
         * UI 에 이메일 입력란이 없고 문구도 "답변은 **가입한** 메일로" 다. 그래서 DTO 에
         * email 필드가 없어야 한다. `spring.jackson` 은 알 수 없는 필드를 무시하도록
         * 돼 있으므로(기본값) 요청은 통과하되 값은 버려진다 — 여기서 확인하는 것은
         * **주소의 출처가 인증 주체 하나뿐**이라는 사실이다.
         */
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "content": "내용",
                      "email": "attacker@evil.test",
                      "senderEmail": "attacker@evil.test"
                    }
                    """))
            .andExpect(status().isOk());

        ArgumentCaptor<String> sender = ArgumentCaptor.forClass(String.class);
        verify(notifier).notifyInquiry(anyString(), sender.capture(), anyString());

        assertEquals(me.getEmail(), sender.getValue(),
            "받는 사람 주소가 인증 주체가 아니라 클라이언트 값에서 왔다");
        assertEquals(1, InquirySendRequest.class.getRecordComponents().length,
            "요청 DTO 가 하나가 아니게 됐다 — 받는 사람 주소를 클라이언트가 정할 수 있게 됐는지 본다");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 검증 실패 — 문구가 화면에 그대로 뜬다

    @Test
    @DisplayName("공백만 보내면 400 이고 사용자가 읽을 수 있는 문구가 온다")
    void whitespaceOnlyIsRejectedWithReadableMessage() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \\n  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Mupid-Error",
                InquiryErrorCode.INQUIRY_CONTENT_REQUIRED.getCode()))
            .andExpect(jsonPath("$.code").value("MUPID_INQUIRY_CONTENT_REQUIRED"))
            /* 대화상자가 e.message 를 그대로 그린다 — 이 문구가 화면에 뜬다. */
            .andExpect(jsonPath("$.message").value("문의 내용을 입력해 주세요."));

        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("내용이 없는 본문도 400 이고 같은 문구다")
    void emptyContentIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("문의 내용을 입력해 주세요."));
    }

    @Test
    @DisplayName("1000자를 넘으면 400 이고 상한이 문구에 들어 있다")
    void tooLongIsRejected() throws Exception {
        String body = "{\"content\":\"%s\"}".formatted("가".repeat(InquirySendRequest.CONTENT_MAX + 1));

        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INQUIRY_CONTENT_TOO_LONG"))
            .andExpect(jsonPath("$.message").value("문의 내용은 1000자를 넘을 수 없습니다."));

        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("정확히 1000자는 통과한다 — 상한이 화면의 maxLength 와 같다")
    void exactMaxIsAccepted() throws Exception {
        String body = "{\"content\":\"%s\"}".formatted("가".repeat(InquirySendRequest.CONTENT_MAX));

        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("본문이 없으면 400 이다 — 500 이 아니다")
    void missingBodyIsBadRequest() throws Exception {
        /* 이 저장소는 클라이언트 잘못을 5xx 로 내보내지 않는다 (GlobalExceptionHandler 주석). */
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_NOT_READABLE_MESSAGE"));
    }

    @Test
    @DisplayName("GET 으로 부르면 500 이 아니라 405 다")
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(asMe())))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("MUPID_METHOD_NOT_ALLOWED"));
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 인증 — 열려 있으면 스팸 통로가 된다

    @Test
    @DisplayName("토큰 없이 부르면 401 이다 — 인증 없는 문의 엔드포인트를 만들지 않았다")
    void requiresAccessToken() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"내용\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("MUPID_UNAUTHORIZED"));

        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("인증이 검증보다 먼저다 — 공백만 보내도 토큰이 없으면 401 이다")
    void authenticationComesBeforeValidation() throws Exception {
        /*
         * 순서가 뒤집히면 로그인하지 않은 쪽이 문의 문구·상한을 떠볼 수 있고, 400 이
         * 인증을 대신하게 된다. 스팸 통로를 만들지 않으려면 401 이 먼저다.
         */
        mockMvc.perform(post(PATH)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("MUPID_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("탈퇴한 계정의 토큰으로는 401 이다")
    void withdrawnUserIsRejected() throws Exception {
        /* `AuthProvider.auth` 가 주체를 DB 에서 다시 읽을 때 탈퇴 계정을 거른다. */
        int userId = me.getUserId();
        em.createQuery("UPDATE User u SET u.status = :status WHERE u.userId = :userId")
            .setParameter("status", UserStatus.WITHDRAW)
            .setParameter("userId", userId)
            .executeUpdate();
        em.flush();
        em.clear();

        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(
                        em.find(User.class, userId), null, List.of())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"내용\"}"))
            /*
             * 컨트롤러가 아니라 서비스가 401 을 낸다 — 목이 아닌 실제 서비스가 인증 주체의
             * 상태를 다시 본다(`InquiryServiceImpl`). 필터 통과 뒤 상태가 바뀐 경우의
             * 마지막 방어선이다.
             */
            .andExpect(status().isUnauthorized());

        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("인증된 요청은 알림까지 도달한다 — 접수와 발송이 이어진다")
    void authenticatedRequestReachesNotifier() throws Exception {
        mockMvc.perform(post(PATH)
                .with(csrf())
                .with(authentication(asMe()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"내용\"}"))
            .andExpect(status().isOk());

        assertEquals(1, org.mockito.Mockito.mockingDetails(notifier).getInvocations().size(),
            "알림이 한 번도 불리지 않았다면 메일도 Mattermost 도 나가지 않는다");
    }

}
