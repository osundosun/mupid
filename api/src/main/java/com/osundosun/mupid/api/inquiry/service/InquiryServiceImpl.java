package com.osundosun.mupid.api.inquiry.service;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.inquiry.error.InquiryErrorCode;
import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 문의 보내기의 접수 절반.
 *
 * <h2>이 클래스가 하지 않는 것</h2>
 *
 * <p><b>메일도 알림도 보내지 않는다.</b> 검증하고 {@link InquiryNotifier} 에 넘긴 뒤
 * 바로 돌아온다. 문의 한 건이 Gmail 왕복 두 번(관리자·문의자)을 기다리면 응답 시간이
 * 그만큼 늘고, 메일이 죽으면 문의 자체를 못 남기게 된다 — 둘 다 계약 위반이다
 * (`infra/deploy/.env.example`: "알림만 조용히 꺼지고 피드백 접수는 정상 동작한다").
 *
 * <h2>DB 에 저장하지 않는다 — 근거</h2>
 *
 * <p>요구는 "관리자에게 메일 + 문의자에게 통보" 이고 <b>보관은 명시되지 않았다.</b>
 * 문의를 담을 기존 테이블도 없다({@code knowledge/domain/erd.sql} 의 30개 표에 없다).
 * 새 테이블은 `erd.sql`·DDL 을 고치는 일이고 그건 팀 승인 사항이라
 * ([decision/0011](.../knowledge/decision/0011-jpa-manages-schema.md), `AGENTS.md`)
 * 이 티켓 범위에서 만들지 않았다. 지금 문의의 기록은 <b>관리자 메일함과 Mattermost
 * 채널</b> 두 곳에 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final InquiryNotifier notifier;

    @Override
    public void sendInquiry(User user, InquirySendRequest request) {
        /*
         * 인증 주체가 없으면 통보할 곳을 알 수 없다. 필터가 막으므로 정상 경로에서는
         * 오지 않지만, 여기서 401 로 끊어 "누구에게도 알리지 않고 200" 이 되는 일을 막는다.
         */
        if (user == null) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }
        /*
         * 탈퇴한 계정이면 받지 않는다.
         *
         * 필터(`AuthProvider.auth`)가 주체를 DB 에서 다시 읽으며 탈퇴 계정을 거르므로
         * 실제 요청에서는 여기 오지 않는다. 그래도 두는 이유는 두 가지다 — 컨트롤러의
         * `Authentication` 을 신뢰하는 경로가 나중에 생기면 탈퇴 계정의 이메일로 접수
         * 통보가 나가고, 문의 내용이 탈퇴한 사람의 메일함에 남는다. 방어선을 하나 더
         * 두는 비용이 여기서는 거의 없다.
         */
        if (user.getStatus() == UserStatus.WITHDRAW) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }

        String content = normalize(request == null ? null : request.content());

        /* 접수 자체는 저장이 없으므로 이 로그가 서버 쪽 유일한 흔적이다. 내용은 남기지 않는다. */
        log.info("문의 접수 — 보낸 사람={} 길이={}", user.getEmail(), content.length());

        try {
            notifier.notifyInquiry(content, user.getEmail(), user.getNickname());
        } catch (RuntimeException e) {
            /*
             * 실행기에 넘기지 못했을 때만 여기로 온다 (큐가 차면 TaskRejectedException).
             * 비동기 본문에서 난 예외는 다른 스레드라 여기 오지 않는다 — 그리고 그 본문은
             * 스스로 실패를 삼킨다.
             *
             * 어느 쪽이든 접수는 이미 끝났다. 알림을 못 넘겼다고 문의를 실패로 돌리면
             * 부가 기능이 본 기능을 막는 셈이다.
             */
            log.warn("문의 알림을 넘기지 못했다 — 접수는 그대로 유지된다", e);
        }
    }

    /**
     * 내용을 다듬고 검증한다.
     *
     * @return trim 된 내용
     * @throws BusinessException 내용이 비었거나(공백만 포함) 상한을 넘을 때. 문구는
     *         {@link InquiryErrorCode} 에 있고 그대로 화면에 뜬다
     */
    private String normalize(String raw) {
        String content = raw == null ? "" : raw.trim();

        /*
         * 프런트가 body.trim().length > 0 일 때만 보내므로 서버도 trim 기준으로 본다.
         * 그냥 length 로 보면 공백만 보낸 요청이 통과해 빈 메일이 나간다.
         */
        if (content.isEmpty()) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_CONTENT_REQUIRED);
        }
        if (content.length() > InquirySendRequest.CONTENT_MAX) {
            throw new BusinessException(InquiryErrorCode.INQUIRY_CONTENT_TOO_LONG);
        }
        return content;
    }

}
