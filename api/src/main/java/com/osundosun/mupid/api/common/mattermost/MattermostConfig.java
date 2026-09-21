package com.osundosun.mupid.api.common.mattermost;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Mattermost 알림 전용 {@link RestClient}.
 *
 * <p>LLM 게이트웨이(`aiRestClient`, 60초)·추천 서버(`recommenderRestClient`, 5초)와
 * <b>또 따로 둔다.</b> 알림은 부가 기능이라 오래 기다릴 이유가 없다 — 짧게 끊고 로그만
 * 남긴다.
 *
 * <p>주소가 비어 있어도 빈은 만든다({@link RecommenderConfig} 와 같은 판단). 호출부가
 * {@link MattermostProperties#isConfigured()} 로 먼저 막으므로 이 빈이 실제로 쓰이는 일은
 * 없고, 여기서 예외를 던지면 알림과 무관한 기능까지 기동이 막힌다.
 */
@Configuration
public class MattermostConfig {

    @Bean
    public RestClient mattermostRestClient(MattermostProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
            .baseUrl(props.isConfigured() ? props.webhookUrl() : "http://mattermost.invalid")
            .requestFactory(factory)
            .build();
    }

}
