package com.osundosun.mupid.api.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * LLM 게이트웨이와 주고받는 JSON 전용 매퍼.
 *
 * 애플리케이션 ObjectMapper 를 쓰지 않는 이유는 `EventServiceImpl` 과 같다.
 *
 * 1. **주입할 빈이 없다.** Spring Boot 4 의 JacksonAutoConfiguration 은 Jackson 3
 *    (`tools.jackson`) 만 등록한다. `com.fasterxml` 의 ObjectMapper 빈은 없다.
 * 2. **이 필드명은 OpenAI 와의 계약이다.** `tool_calls`·`tool_call_id`·`finish_reason`
 *    은 애플리케이션 설정(`spring.jackson.property-naming-strategy`)이 아니라 저쪽 API 가
 *    정한다. 설정에 묶어두면 누가 그 값을 바꾸는 순간 툴 호출이 조용히 사라진다.
 *
 * 본문은 문자열이 아니라 **바이트로** 오간다. `application/json` 에 charset 을 적지 않아도
 * 한글이 깨지지 않게 하려는 것이다 — 대화 내용은 대부분 한국어다.
 */
public final class AiJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .defaultPropertyInclusion(JsonInclude.Value.construct(
            JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        // 게이트웨이가 필드를 늘려도 (usage·service_tier·logprobs …) 깨지지 않아야 한다.
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private AiJson() {
    }

    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(AiErrorCode.AI_REQUEST_FAILED);
        }
    }

    public static String writeString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(AiErrorCode.AI_REQUEST_FAILED);
        }
    }

    public static <T> T read(byte[] json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BusinessException(AiErrorCode.AI_REQUEST_FAILED);
        }
    }

    /**
     * 툴 호출 인자를 읽는다. **모델이 만든 문자열이라 JSON 이 아닐 수 있다** —
     * 그때는 예외 대신 null 을 돌려주고, 호출부가 기본값으로 진행한다.
     */
    public static <T> T readArguments(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

}
