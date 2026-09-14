package com.osundosun.mupid.api.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.osundosun.mupid.api.ai.properties.AiProperties;

/**
 * LLM 게이트웨이 전용 `RestClient`.
 *
 * 저장소에서 밖으로 나가는 세 번째 경로다 (앞의 둘은 S3 · Gmail). S3 가 `S3Client` 를
 * 직접 만들 듯 여기도 래퍼 없이 `RestClient` 하나만 만든다.
 *
 * `Authorization` 헤더는 **여기서 붙이지 않는다.** 키가 비어 있을 때 `Bearer ` 만 실린
 * 요청이 나가면 게이트웨이의 401 을 우리 오류로 오해하게 된다. 키 검사는 호출부
 * (`OpenAiClient`)가 하고, 없으면 요청 자체를 만들지 않는다.
 */
@Configuration
public class AiConfig {

    @Bean
    public RestClient aiRestClient(AiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());

        return RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .build();
    }

}
