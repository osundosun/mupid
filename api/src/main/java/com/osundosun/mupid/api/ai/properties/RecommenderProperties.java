package com.osundosun.mupid.api.ai.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천 서버(`ai_server/`) 연결 설정.
 *
 * <p>LLM 게이트웨이 설정({@link AiProperties})과 **다른 서버**다. 이름이 비슷해 헷갈리기
 * 쉬운데, 저쪽은 대화를 만들고 이쪽은 곡을 고른다.
 *
 * <p>환경변수 이름을 {@code MUPID_AI_BASE_URL} 로 쓰지 않는다. 그 이름은 이미 LLM
 * 게이트웨이가 쓰고 있어서, 추천 서버 주소를 거기에 넣으면 **대화가 추천 서버로 나간다.**
 * (`ai_server/README.md` 가 한때 그 이름을 안내하고 있었다.)
 *
 * <p>주소가 비어 있으면 추천 호출을 하지 않는다. 대화 자체는 계속 되고, 곡 추천만
 * "준비되지 않았다" 로 답한다 — 없는 곡을 지어내는 것보다 낫다.
 */
@ConfigurationProperties(prefix = "recommender")
public record RecommenderProperties(
    /**
     * `/internal` 앞까지의 주소. 끝에 슬래시를 붙이지 않는다.
     *
     * <p>**nginx 에 공개하지 않는다.** `/internal` 에는 인증이 없고 Spring 만 부르는
     * 전제다. 컨테이너에서 호스트의 서비스를 부른다면 `extra_hosts` 를 맞춰야 한다.
     */
    String baseUrl,
    @DefaultValue("2s") Duration connectTimeout,
    /**
     * 읽기 타임아웃. 배포 규약이 정한 값은 5초다.
     *
     * <p>이력이 있는 사용자는 응답이 p50 2.2초까지 나온다(행동 로그 전수 스캔). 5초는
     * 그 위에 얹은 여유다 — 더 줄이면 개인화된 사용자만 골라서 실패한다.
     */
    @DefaultValue("5s") Duration readTimeout,
    /**
     * 실패했을 때 한 번 더 부를지. 규약상 재시도 대상은 500·503·타임아웃뿐이다.
     *
     * <p>400·404 는 다시 불러도 같은 답이 온다. 특히 404 `NO_CANDIDATES` 는 장애가
     * 아니라 "조건에 맞는 곡이 없다" 라서 재시도하면 사용자만 기다리게 된다.
     */
    @DefaultValue("true") boolean retryOnce
) {
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank();
    }
}
