package com.osundosun.mupid.api.common.mattermost;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mattermost Incoming Webhook 한 방 호출.
 *
 * <p>Incoming Webhook 이라 자격증명이 곧 URL 이다. 그래서 이 클래스가 지키는 규칙이
 * 하나 있다 — <b>웹훅 URL 을 로그에 남기지 않는다.</b> 실패 로그에 예외를 그대로 넘기면
 * {@code RestClientException} 메시지 안에 요청 URL 이 들어 있어 그대로 새어 나간다
 * ({@code I/O error on POST request for "https://…/hooks/…"}). 그래서 여기서는 예외를
 * 문자열로 펴기 전에 URL 을 가린다 ({@link #mask}).
 *
 * <p>이 클래스는 <b>던지지 않는다.</b> 알림은 부가 기능이고, 여기서 예외가 올라가면
 * 문의 접수가 실패한다 — `infra/deploy/.env.example` 의 계약("알림이 죽어도 접수는
 * 정상")을 지키려면 실패는 로그로 끝나야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MattermostNotifier {

    /** 로그에 남는 URL 대신 넣는 자리표시자. 어느 웹훅인지는 알아야 디버깅이 된다. */
    static final String MASKED_URL = "<webhook-url>";

    /** 웹훅이 받는 본문. `{"text": "..."}` 이면 채널에 그대로 그려진다. */
    private record Message(String text) {

    }

    private final RestClient mattermostRestClient;
    private final MattermostProperties props;

    /** 웹훅이 설정돼 있는가. 호출부가 "건너뛰었다" 를 로그로 남길 때 쓴다. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * 채널에 한 줄 알린다.
     *
     * <p>웹훅이 비어 있으면 <b>조용히 건너뛰고 로그만 남긴다</b> — 꺼진 것이 정상 동작이다.
     * 보내다 실패해도 마찬가지로 삼킨다.
     *
     * @param text 채널에 그릴 본문(Mattermost 마크다운)
     */
    public void notify(String text) {
        if (!isConfigured()) {
            // URL 은 찍지 않는다. 비어 있다는 사실만 남긴다.
            log.info("Mattermost 웹훅이 설정되지 않아 알림을 건너뛴다 (MUPID_MATTERMOST_WEBHOOK_URL)");
            return;
        }
        try {
            mattermostRestClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Message(text))
                .retrieve()
                .toBodilessEntity();
            /*
             * 성공도 남긴다. 없으면 "알림이 나갔다" 와 "알림 코드가 아예 안 불렸다" 를
             * 로그로 구분할 수 없다 — 실패는 WARN 으로 남는데 성공이 침묵하면 운영자가
             * 채널을 눈으로 확인하는 것 말고는 방법이 없다. URL·본문은 남기지 않는다.
             */
            log.info("Mattermost 로 문의 알림을 보냈다");
        } catch (RuntimeException e) {
            /*
             * 연결 실패·타임아웃·4xx/5xx 를 전부 여기서 끝낸다. 알림이 죽었다고 문의
             * 접수가 실패하면 안 된다.
             *
             * 예외 메시지를 그대로 넘기지 않는 이유는 위 클래스 주석 참고 — 요청 URL 이
             * 메시지에 들어 있다.
             */
            log.warn("Mattermost 알림을 보내지 못했다 — 문의 접수는 그대로 유지된다: {}",
                mask(e.toString()));
        }
    }

    /**
     * 예외·메시지에서 웹훅 URL 을 지운다.
     *
     * <p>설정된 URL 뿐 아니라 {@code hooks/} 가 보이는 주소도 함께 가린다 — 예외가
     * 리다이렉트나 다른 표기로 URL 을 다시 실어 올 수 있다.
     *
     * <p>패키지 가시성인 이유는 이 규칙 자체를 테스트가 직접 확인하기 때문이다
     * ({@code MattermostNotifierTest}). 로그 문구를 정규식으로 더듬는 것보다 낫다.
     */
    String mask(String message) {
        if (message == null) {
            return null;
        }
        String masked = message;
        if (props.isConfigured()) {
            masked = masked.replace(props.webhookUrl(), MASKED_URL);
        }
        return masked.replaceAll("https?://[^\\s\"]*hooks/[^\\s\"]*", MASKED_URL);
    }

}
