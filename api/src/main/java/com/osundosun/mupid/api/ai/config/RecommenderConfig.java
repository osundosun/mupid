package com.osundosun.mupid.api.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.properties.RecommenderProperties;

/**
 * 추천 서버 전용 {@link RestClient}.
 *
 * <p>LLM 게이트웨이의 {@code aiRestClient} 와 **따로 둔다.** 타임아웃이 다르기 때문이다 —
 * 저쪽은 추론 모델을 기다리느라 60초이고 이쪽은 5초다. 하나로 합치면 추천 서버가 죽었을 때
 * 요청 스레드가 1분씩 묶인다.
 *
 * <p>주소가 비어 있어도 빈은 만든다. 호출부가 {@code isConfigured()} 로 먼저 막으므로
 * 이 빈이 실제로 쓰이는 일은 없고, 여기서 예외를 던지면 추천과 무관한 기능까지 기동이
 * 막힌다.
 */
@Configuration
public class RecommenderConfig {

    @Bean
    public RestClient recommenderRestClient(RecommenderProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return RestClient.builder()
            .baseUrl(props.isConfigured() ? props.baseUrl() : "http://recommender.invalid")
            .requestFactory(factory)
            .build();
    }
}
