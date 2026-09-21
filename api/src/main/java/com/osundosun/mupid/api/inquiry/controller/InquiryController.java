package com.osundosun.mupid.api.inquiry.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.inquiry.service.InquiryService;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 문의하기 (S15P21A204-384).
 *
 * <h2>계약은 프런트가 정했다 — 바꾸지 않는다</h2>
 *
 * `frontend/lib/api/endpoints.ts` 의 `inquiry.send` 가 고정한 모양이다.
 *
 * <pre>
 *   inquiry: {
 *     send: (content: string) =&gt;
 *       client.request&lt;void&gt;({
 *         method: 'POST',
 *         path: '/api/v1/inquiries',
 *         json: { content },
 *         responseType: 'none',
 *       }),
 *   },
 * </pre>
 *
 * <ul>
 *   <li><b>경로</b> `POST /api/v1/inquiries`</li>
 *   <li><b>본문</b> {@code {"content": "..."}} 하나. 이메일은 받지 않는다</li>
 *   <li><b>응답</b> 본문 없음 — {@code responseType: 'none'} 이라 본문을 만들면 오히려
 *       어긋난다. 그래서 {@code ResponseEntity<Void>} 이고 {@code message} 도 없다.
 *       204(NO_CONTENT) 가 아니라 <b>200</b> 인 이유는 아래 참고.</li>
 * </ul>
 *
 * <h2>왜 204 가 아니라 200 인가</h2>
 *
 * <p>204 도 본문이 없어 계약 자체는 맞지만, 이 저장소의 오류 응답이 본문 있는 JSON
 * 이라 성공만 본문이 사라지면 클라이언트가 두 가지를 따로 다뤄야 한다. 프런트는
 * 2xx 면 본문을 읽지 않으므로(`transport.ts`: {@code responseType === 'none'} 이면
 * 파싱하지 않는다) 200 이어도 계약 위반이 아니다. 기존 생성 API 들도 200 을 쓴다.
 *
 * <h2>인증</h2>
 *
 * <p>{@code SecurityConfig} 에 {@code permitAll} 을 추가하지 않았다. 기본값
 * {@code anyRequest().authenticated()} 가 그대로 적용돼 <b>액세스 토큰이 필요</b>하다.
 * 문의는 스팸 통로가 되기 쉬워 열어 둘 이유가 없다.
 *
 * <p>보낸 사람 이메일은 <b>{@code Authentication} 의 주체에서</b> 얻는다. 클라이언트가
 * 보낸 주소를 쓰지 않는다 — 그러면 아무 주소나 넣어 관리자 메일을 스팸 발송기로 만들 수
 * 있고, 문의자 통보도 엉뚱한 사람에게 간다.
 */
@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    /**
     * 문의를 보낸다. 200 이면 접수된 것이고, 메일·알림은 비동기로 나간다.
     *
     * <p><b>접수는 발송 성공에 의존하지 않는다.</b> Gmail 자격증명이 죽어 있거나
     * Mattermost 웹훅이 없어도 200 이다 — 사용자가 문의를 못 남기면 안 되기 때문이다.
     *
     * @param auth    액세스 토큰의 주체. 이 사람의 이메일로 접수 통보가 나간다
     * @param request 내용 하나
     * @return 200 본문 없음. 400 {@code MUPID_INQUIRY_CONTENT_REQUIRED}(빈 값·공백만) /
     *         {@code MUPID_INQUIRY_CONTENT_TOO_LONG}(1000자 초과) — 문구는 화면에 그대로
     *         뜬다. 401 {@code MUPID_UNAUTHORIZED} — 토큰이 없거나 유효하지 않음.
     *         400 {@code MUPID_NOT_READABLE_MESSAGE} — 본문 누락·파싱 실패
     */
    @PostMapping
    public ResponseEntity<Void> sendInquiry(
        Authentication auth,
        @RequestBody InquirySendRequest request
    ) {
        User user = (User)auth.getPrincipal();
        inquiryService.sendInquiry(user, request);
        return ResponseEntity.status(HttpStatus.OK).build();
    }

}
