package com.osundosun.mupid.api.inquiry.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import com.osundosun.mupid.api.common.config.AsyncConfig;
import com.osundosun.mupid.api.common.gmail.GmailProperties;
import com.osundosun.mupid.api.common.gmail.GmailProvider;
import com.osundosun.mupid.api.common.mattermost.MattermostNotifier;
import com.osundosun.mupid.api.email.service.EmailTemplateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 문의 알림 — 관리자 메일 · 문의자 통보 · Mattermost.
 *
 * <h2>왜 접수와 다른 빈인가</h2>
 *
 * <p>{@code @Async} 는 프록시로 동작한다. 같은 빈 안에서 자기 메서드를 부르면 프록시를
 * 거치지 않아 <b>조용히 동기로 돈다</b>. 그래서 접수({@link InquiryServiceImpl})와
 * 발송을 다른 빈으로 갈랐다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>이 클래스에는 {@code @Transactional} 이 없다. 문의는 DB 에 저장하지 않으므로
 * 비동기 스레드가 "커밋 전 데이터를 못 보는" 문제 자체가 없다 — 커밋될 것이 없다.
 * 나중에 문의를 저장하게 되면 <b>여기에 트랜잭션을 열지 말고</b>, 접수 트랜잭션이 끝난
 * 뒤 넘기거나(AFTER_COMMIT) 넘길 때 값을 다 실어 보내야 한다. 비동기 스레드에서 새
 * 트랜잭션을 열면 커밋 전 데이터를 읽지 못한다.
 *
 * <h2>실패 격리</h2>
 *
 * <p>세 채널(관리자 메일·문의자 메일·Mattermost)이 <b>각각 따로</b> 예외를 삼킨다.
 * 관리자 메일이 죽었다고 문의자가 접수 통보를 못 받으면 안 되고, 메일이 전부 죽었다고
 * Mattermost 알림까지 사라지면 안 된다. 어느 하나도 접수에 영향을 주지 않는다 —
 * 접수는 이 메서드가 불리기 전에 이미 끝났다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryNotifier {

    /** 관리자 메일 제목. 제목 형식은 `EmailServiceImpl` 의 `[뮤피드] %s` 를 따른다. */
    private static final String ADMIN_SUBJECT = "[뮤피드] 새 문의가 도착했습니다";

    /** 문의자 통보 제목. */
    private static final String RECEIPT_SUBJECT = "[뮤피드] 문의가 접수되었습니다";

    private static final String ADMIN_TEMPLATE = "mail/inquiry-admin";
    private static final String RECEIPT_TEMPLATE = "mail/inquiry-receipt";

    /** 관리자 메일 본문에 찍는 접수 시각. 서버 시간대(`Asia/Seoul`) 기준이다. */
    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 메일용 로고. `EmailServiceImpl.MAIL_LOGO_PATH` 와 같은 값이다. */
    private static final String MAIL_LOGO_PATH = "/mupid-logo-email.png";

    private final GmailProvider gmailProvider;
    private final GmailProperties gmailProps;
    private final EmailTemplateService templateService;
    private final MattermostNotifier mattermostNotifier;

    /**
     * `mupid-client.website`. 값이 없으면 빈 문자열이고 기본값이 있다
     * (`application.yaml`) — 없어도 기동이 막히지 않아야 하므로 필드로 받는다.
     */
    @Value("${mupid-client.website}")
    private String mupidClientWebsite;

    /**
     * 세 채널로 알린다. 요청 스레드가 아니라 {@code inquiryNotifierExecutor} 에서 돈다.
     *
     * @param content     trim 이 끝난 문의 내용
     * @param senderEmail 인증 주체의 이메일. 관리자 메일 본문에 답장용으로 실린다
     * @param senderName  인증 주체의 닉네임. 문의자 통보에만 쓴다
     */
    @Async(AsyncConfig.INQUIRY_NOTIFIER_EXECUTOR)
    public void notifyInquiry(String content, String senderEmail, String senderName) {
        String contentHtml = toHtmlBody(content);
        LocalDateTime receivedAt = LocalDateTime.now();

        notifyAdmin(contentHtml, senderEmail, senderName, receivedAt);
        notifySender(contentHtml, senderEmail, senderName);
        notifyMattermost(content, senderEmail, senderName);
    }

    /**
     * 관리자에게 — <b>자기 자신에게 메일 보내기</b>.
     *
     * <p>받는 주소는 {@link GmailProperties#sendAddress()}(= `MUPID_GMAIL_SEND_ADDRESS`)다.
     * 발신 계정과 같은 주소로 보내는 것이 "자기자신에게" 의 뜻이다. 그래서 관리자 메일의
     * 답장은 문의자에게 가지 않는다 — 그래서 본문에 문의자 주소를 명시해 두었다.
     */
    private void notifyAdmin(String contentHtml, String senderEmail, String senderName,
                             LocalDateTime receivedAt) {
        Map<String, Object> variables = logoVariables();
        variables.put("contentHtml", contentHtml);
        variables.put("senderEmail", senderEmail);
        variables.put("senderName", senderName);
        variables.put("receivedAt", receivedAt.format(RECEIVED_AT_FORMAT));

        sendMail(gmailProps.sendAddress(), ADMIN_SUBJECT, ADMIN_TEMPLATE, variables, "관리자");
    }

    /** 문의한 사용자에게 접수 통보. 주소는 인증 주체에서 얻은 값 그대로다. */
    private void notifySender(String contentHtml, String senderEmail, String senderName) {
        Map<String, Object> variables = logoVariables();
        variables.put("contentHtml", contentHtml);
        variables.put("senderName", senderName);

        sendMail(senderEmail, RECEIPT_SUBJECT, RECEIPT_TEMPLATE, variables, "문의자");
    }

    /**
     * 메일 한 통. 실패는 로그로 끝낸다.
     *
     * @param whom 로그에만 쓰는 대상 표시("관리자"/"문의자"). 주소는 로그에 남기지 않는다
     */
    private void sendMail(String to, String subject, String template, Map<String, Object> variables,
                          String whom) {
        try {
            String html = templateService.buildHtml(template, variables);
            /*
             * 로컬·CI 에는 실제 Gmail 자격증명이 없다(`_shared/mupid-test-env.sh` 가
             * test-only 값을 넣는다). 그래서 여기서 GmailException 이 나는 것이 정상이고,
             * 그 실패가 접수(이미 끝난 일)에 영향을 주지 않아야 한다.
             */
            gmailProvider.sendEmail(to, subject, html);
            log.info("문의 {} 메일을 보냈다", whom);
        } catch (RuntimeException e) {
            /* 스택은 남기되 주소·본문은 남기지 않는다. 운영자가 상태를 알 수 있으면 된다. */
            log.error("문의 {} 메일에 실패했다 — 접수는 그대로 유지된다: {}",
                whom, e.getMessage(), e);
        }
    }

    /**
     * Mattermost 알림.
     *
     * <p>웹훅 URL·문의 내용을 로그에 남기지 않는다. URL 이 곧 자격증명이라
     * {@link MattermostNotifier} 가 실패 로그에서까지 가린다.
     */
    private void notifyMattermost(String content, String senderEmail, String senderName) {
        /* 보낸 사람을 알아야 관리자가 채널에서 바로 답할 수 있다. */
        String text = """
            **새 문의가 도착했습니다**
            - 보낸 사람: %s (%s)
            - 내용: %s""".formatted(senderName, senderEmail, content);
        mattermostNotifier.notify(text);
    }

    /** 메일 공통 레이아웃의 로고. 웹사이트 주소가 없으면 템플릿이 글자 워드마크로 대체한다. */
    private Map<String, Object> logoVariables() {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (mupidClientWebsite != null && !mupidClientWebsite.isBlank()) {
            variables.put("logoUrl", buildMailLogoUrl(mupidClientWebsite));
        }
        return variables;
    }

    /**
     * `EmailServiceImpl.buildMailLogoUrl` 과 같은 규칙이다 — 웹사이트 주소의 경로·쿼리를
     * 버리고 로고 파일 경로만 남긴다. 그 메서드가 패키지 밖에서 안 보여(패키지 사설)
     * 여기에 같은 것을 둔다. 그 클래스의 가시성을 넓히지 않으려는 선택이다.
     */
    static String buildMailLogoUrl(String websiteUrl) {
        return UriComponentsBuilder.fromUriString(websiteUrl)
            .replacePath(MAIL_LOGO_PATH)
            .replaceQuery(null)
            .fragment(null)
            .encode()
            .toUriString();
    }

    /**
     * 문의 내용을 메일 본문용 HTML 한 덩어리로 만든다.
     *
     * <p><b>먼저 escape 하고 나서 줄바꿈만 태그로 바꾼다.</b> 순서를 뒤집으면 사용자가 쓴
     * `<b>` 가 그대로 살아 메일 본문에 HTML 이 주입된다. 결과는 템플릿이 `th:utext` 로
     * 그리므로, 여기서 escape 를 빠뜨리면 그대로 구멍이 된다.
     *
     * <p>줄바꿈은 `<br />` 로 바꾼다. 메일 클라이언트가 `<style>`·`white-space` 를 자주
     * 잘라내기 때문이다(`mail/fragments/layout.html` 주석과 같은 이유).
     */
    static String toHtmlBody(String content) {
        if (content == null) {
            return "";
        }
        return HtmlUtils.htmlEscape(content)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "<br />");
    }

}
