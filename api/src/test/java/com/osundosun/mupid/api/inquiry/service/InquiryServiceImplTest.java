package com.osundosun.mupid.api.inquiry.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.inquiry.error.InquiryErrorCode;
import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

/**
 * 문의 접수의 검증과 실패 격리를 못박는다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * <ol>
 *   <li><b>빈 값·공백만·길이 초과가 거부된다.</b> 프런트가 {@code body.trim().length > 0}
 *       일 때만 보내지만 그것을 계약으로 삼지 않는다 — {@code maxLength} 는 UI 제약이고,
 *       curl 로도 부를 수 있다. 그리고 거부 문구는 <b>화면에 그대로 뜨는 문자열</b>이라
 *       코드가 아니라 문구 자체를 확인한다.</li>
 *   <li><b>trim 이 길이 검사보다 먼저다.</b> 순서가 뒤집히면 뒤에 공백을 붙인 1000자
 *       문의가 거부된다.</li>
 *   <li><b>보낸 사람은 인증 주체에서만 온다.</b> 요청 DTO 에 이메일 필드가 없다는 것이
 *       이 테스트의 요점이다 — 넣을 자리가 없으니 클라이언트가 정할 수 없다.</li>
 *   <li><b>알림을 넘기지 못해도 접수는 성공한다.</b> 실행기 큐가 차면
 *       {@code TaskRejectedException} 이 요청 스레드로 올라오는데, 그때 문의가 실패하면
 *       부가 기능이 본 기능을 막는 셈이다.</li>
 * </ol>
 *
 * <p>Mattermost 본문에 실리는 문자열 자체는 {@code InquiryMattermostBodyTest} 가 본다 —
 * 여기서는 "넘겼는가" 까지만 본다.
 */
class InquiryServiceImplTest {

    private InquiryNotifier notifier;
    private InquiryServiceImpl service;

    @BeforeEach
    void setUp() {
        notifier = mock(InquiryNotifier.class);
        service = new InquiryServiceImpl(notifier);
    }

    private User user(String email, String nickname) {
        return User.builder().email(email).nickname(nickname).status(UserStatus.ACTIVE).build();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 검증

    @Test
    @DisplayName("내용이 없으면 400 이고 문구가 화면에 뜨는 그대로다")
    void rejectsMissingContent() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(user("me@mupid.test", "나"), new InquirySendRequest(null)));

        assertEquals(InquiryErrorCode.INQUIRY_CONTENT_REQUIRED, e.getErrorCode());
        assertEquals("문의 내용을 입력해 주세요.", e.getMessage());
        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("공백만 보낸 요청은 trim 기준으로 거부된다")
    void rejectsWhitespaceOnly() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(user("me@mupid.test", "나"),
                new InquirySendRequest("   \n\t  ")));

        /* 프런트가 trim 해서 보내므로 서버도 trim 으로 봐야 한다. length 로 보면 통과한다. */
        assertEquals(InquiryErrorCode.INQUIRY_CONTENT_REQUIRED, e.getErrorCode());
        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("요청 본문 자체가 없어도(필드 누락) 같은 문구로 거부된다")
    void rejectsNullRequest() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(user("me@mupid.test", "나"), null));

        assertEquals(InquiryErrorCode.INQUIRY_CONTENT_REQUIRED, e.getErrorCode());
    }

    @Test
    @DisplayName("1000자는 통과하고 1001자는 거부된다 — 상한이 화면의 maxLength 와 같다")
    void enforcesContentMax() {
        User me = user("me@mupid.test", "나");

        assertDoesNotThrow(() -> service.sendInquiry(me,
            new InquirySendRequest("가".repeat(InquirySendRequest.CONTENT_MAX))));

        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(me,
                new InquirySendRequest("가".repeat(InquirySendRequest.CONTENT_MAX + 1))));

        assertEquals(InquiryErrorCode.INQUIRY_CONTENT_TOO_LONG, e.getErrorCode());
        assertEquals("문의 내용은 1000자를 넘을 수 없습니다.", e.getMessage());
    }

    @Test
    @DisplayName("trim 을 먼저 하고 길이를 본다 — 뒤 공백은 상한에 세지 않는다")
    void trimsBeforeMeasuringLength() {
        User me = user("me@mupid.test", "나");

        assertDoesNotThrow(() -> service.sendInquiry(me,
            new InquirySendRequest("가".repeat(InquirySendRequest.CONTENT_MAX) + "     ")));

        /* 알림에 넘어간 값도 trim 된 상태여야 한다 — 메일에 앞뒤 공백이 그려지면 안 된다. */
        verify(notifier).notifyInquiry("가".repeat(InquirySendRequest.CONTENT_MAX),
            "me@mupid.test", "나");
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 보낸 사람은 인증 주체에서 온다

    @Test
    @DisplayName("알림의 보낸 사람은 요청이 아니라 인증 주체에서 온다")
    void senderComesFromPrincipal() {
        service.sendInquiry(user("real@mupid.test", "진짜"), new InquirySendRequest("내용"));

        verify(notifier).notifyInquiry("내용", "real@mupid.test", "진짜");
    }

    @Test
    @DisplayName("인증 주체가 없으면 401 이다 — 아무에게도 알리지 않고 200 을 주지 않는다")
    void rejectsMissingPrincipal() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(null, new InquirySendRequest("내용")));

        assertEquals(AuthErrorCode.UNAUTHORIZED, e.getErrorCode());
        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("탈퇴한 계정이면 401 이다 — 탈퇴자 메일함으로 문의 내용이 가지 않는다")
    void rejectsWithdrawnPrincipal() {
        User withdrawn = User.builder()
            .email("gone@mupid.test").nickname("탈퇴자").status(UserStatus.WITHDRAW).build();

        BusinessException e = assertThrows(BusinessException.class,
            () -> service.sendInquiry(withdrawn, new InquirySendRequest("내용")));

        assertEquals(AuthErrorCode.UNAUTHORIZED, e.getErrorCode());
        verify(notifier, never()).notifyInquiry(anyString(), anyString(), anyString());
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 알림 실패가 접수를 막지 않는다

    @Test
    @DisplayName("실행기가 알림을 거절해도 접수는 성공한다")
    void keepsAcceptanceWhenNotifierRejects() {
        /*
         * 큐가 찼을 때 실제로 올라오는 예외다. 접수는 이미 끝난 일이라 이 예외가
         * 컨트롤러까지 가면 500 이 나가고 사용자는 문의를 못 남긴다.
         */
        doThrow(new TaskRejectedException("큐가 찼다"))
            .when(notifier).notifyInquiry(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.sendInquiry(user("me@mupid.test", "나"),
            new InquirySendRequest("내용")));

        verify(notifier).notifyInquiry(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("알림 경로가 통째로 죽어도 접수는 성공한다")
    void keepsAcceptanceWhenNotifierBlowsUp() {
        /* 메일·알림이 어떤 이유로든 실패해도 접수는 살아야 한다 — 이 티켓의 핵심 계약. */
        doThrow(new IllegalStateException("무엇이든"))
            .when(notifier).notifyInquiry(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.sendInquiry(user("me@mupid.test", "나"),
            new InquirySendRequest("내용")));
    }

    @Test
    @DisplayName("접수 경로에는 발송 협력자가 없다 — 메일이 요청 스레드에 끼어들 자리가 없다")
    void doesNotHoldMailCollaboratorsOnTheRequestPath() {
        /*
         * `InquiryServiceImpl` 의 생성자 인자가 `InquiryNotifier` 하나뿐이라는 것이 요점이다.
         * `GmailProvider`·`MattermostNotifier`·`EmailTemplateService` 가 여기 있으면
         * 발송이 요청 스레드에서 돌 수 있고, 한 통이 죽으면 접수도 함께 죽는다.
         *
         * 필드 목록이 아니라 **생성자 파라미터 타입**을 본다 — `@Slf4j` 가 필드를 하나 더
         * 넣기 때문이다(log).
         */
        var declared = InquiryServiceImpl.class.getDeclaredConstructors();
        assertEquals(1, declared.length);
        assertEquals(List.of(InquiryNotifier.class), List.of(declared[0].getParameterTypes()),
            "접수 경로에 발송 협력자가 들어왔다 — 그 순간 메일 실패가 접수를 막을 수 있게 된다");
    }

}
