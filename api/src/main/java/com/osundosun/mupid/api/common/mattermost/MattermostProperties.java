package com.osundosun.mupid.api.common.mattermost;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mattermost Incoming Webhook 설정 (S15P21A204-384).
 *
 * <p>Incoming Webhook 이므로 <b>URL 자체가 자격증명이다.</b> 별도 토큰·키 파라미터가
 * 없고, URL 을 아는 쪽은 그 채널에 아무 메시지나 보낼 수 있다. 그래서 이 값은
 * Secret(`MUPID_MATTERMOST_WEBHOOK_URL`)으로 들어오고 <b>로그·응답에 절대 적지 않는다.</b>
 *
 * <p><b>비어 있으면 알림만 조용히 꺼진다.</b> 문의 접수(메일 발송·HTTP 응답)는 이 값과
 * 무관하게 동작해야 한다 — `infra/deploy/.env.example` 의 계약이고, 없다고 파드가
 * 못 뜨면 안 되므로 k8s 에서도 `optional: true` 다.
 */
@ConfigurationProperties(prefix = "mupid-mattermost")
public record MattermostProperties(

    /**
     * 웹훅 주소. 비어 있으면 알림을 보내지 않는다(정상 동작).
     *
     * <p>문의 한 건의 응답 시간이 알림 때문에 늘어나면 안 되므로 타임아웃은 짧게 잡는다.
     * 어차피 알림은 비동기 스레드에서 나가지만, 그 스레드가 오래 묶이면 뒤따르는 문의의
     * 알림이 밀린다.
     */
    String webhookUrl,
    @DefaultValue("2s") Duration connectTimeout,
    @DefaultValue("3s") Duration readTimeout

) {

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

}
