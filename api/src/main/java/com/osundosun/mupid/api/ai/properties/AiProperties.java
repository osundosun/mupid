package com.osundosun.mupid.api.ai.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OpenAI 호환 LLM 게이트웨이 설정.
 *
 * SSAFY GMS 프록시(`https://gms.ssafy.io/gmsapi/api.openai.com/v1`)를 기본값으로 둔다.
 * 경로만 OpenAI 와 같으면 되므로 다른 게이트웨이나 로컬 모델로 갈아탈 때 `MUPID_AI_BASE_URL`
 * 하나만 바꾸면 된다.
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(

    /**
     * `/chat/completions` 앞까지의 주소. 끝에 슬래시를 붙이지 않는다.
     */
    @DefaultValue("https://gms.ssafy.io/gmsapi/api.openai.com/v1") String baseUrl,

    /**
     * `Authorization: Bearer` 에 실리는 키. 비어 있으면 대화 API 가 503 을 낸다.
     */
    String apiKey,

    @DefaultValue("gpt-5-mini") String model,

    @DefaultValue("5s") Duration connectTimeout,

    /**
     * 응답을 기다리는 최대 시간.
     *
     * 추론 모델은 첫 토큰까지 수십 초가 걸린다. 다만 이 시간만큼 **요청 스레드가 묶이므로**
     * 무한정 늘리지 않는다. 스트리밍을 붙이면 그때 다시 잡는다.
     */
    @DefaultValue("60s") Duration readTimeout,

    /**
     * 툴 호출 → 결과 주입 → 재질의를 몇 번까지 반복할 것인가.
     *
     * 모델이 같은 툴을 계속 부르며 끝내지 않는 경우가 있어 상한을 둔다. 넘으면 502 다.
     */
    @DefaultValue("3") int maxToolRounds,

    /**
     * 클라이언트가 보낼 수 있는 이전 대화 수. 넘치면 **최근 것부터** 남기고 자른다.
     */
    @DefaultValue("20") int maxHistory

) {

    private static final String DEFAULT_BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-5-mini";

    /**
     * 빈 문자열을 기본값으로 되돌린다.
     *
     * `${MUPID_AI_BASE_URL:https://...}` 의 기본값은 **변수가 없을 때만** 쓰인다.
     * `.env` 에 `MUPID_AI_BASE_URL=` 처럼 키만 두면 변수는 "있고 값이 빈 것" 이라
     * 기본값이 무시되고 주소가 통째로 비어 버린다. `.env.example` 이 키를 비워 두는
     * 규약이라 실제로 밟게 되는 함정이다.
     */
    public AiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
    }

    /**
     * 키가 없으면 호출 자체를 하지 않는다. 게이트웨이의 401 을 기다릴 이유가 없다.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

}
