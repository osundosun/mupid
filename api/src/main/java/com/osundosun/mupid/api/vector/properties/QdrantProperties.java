package com.osundosun.mupid.api.vector.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Qdrant 쓰기 연결 설정 (S15P21A204-457, 설계 §6.4·§6.6).
 *
 * <p><b>Spring 이 Qdrant 에 쓰는 유일한 쪽이다</b>(원칙 1). 그래서 쓰기 키
 * ({@code MUPID_QDRANT_API_KEY}, {@code MUPID_API_ENV} → {@code mupid-secrets})를 받는 것도
 * 여기뿐이다. ai_server 는 읽기 전용 키만 갖는다.
 *
 * <p>주소가 비어 있으면 벡터 API 만 503 이고 나머지는 그대로 뜬다 — 추천 서버 설정
 * ({@code RecommenderProperties})과 같은 판단이다. 곡·구간 등록은 벡터와 따로 커밋되므로
 * Qdrant 가 없는 dev 에서도 막히지 않는다.
 */
@ConfigurationProperties(prefix = "qdrant")
public record QdrantProperties(
    /** REST 주소({@code http://qdrant:6333}). 끝에 슬래시를 붙이지 않는다. */
    String url,
    /** 쓰기 키. 헤더 {@code api-key} 로만 싣는다 — 로그·응답에 적지 않는다. */
    String apiKey,
    /** 비우면 정의 파일({@code qdrant/qdrant_collection.json})의 이름을 쓴다. */
    String collection,
    @DefaultValue("2s") Duration connectTimeout,
    /**
     * 읽기 타임아웃. {@code wait=true} 로 쓰므로 Qdrant 가 색인에 반영할 때까지 기다린다 —
     * 곡 하나(구간 약 9개)는 수십 ms 지만 병합이 겹치면 늘어난다.
     */
    @DefaultValue("30s") Duration readTimeout,
    /**
     * 벡터 쓰기를 동시에 몇 개까지 돌릴지(설계 §10.3-7). 모델 재계산은 1,000구간 요청을 수백 번
     * 보낸다. 이 수만큼만 요청 스레드를 잡고, 넘치면 기다리게 한다.
     */
    @DefaultValue("2") int writeConcurrency,
    /** 자리가 없을 때 기다릴 줄의 길이. 이것도 차면 바로 429 로 돌려보낸다. */
    @DefaultValue("8") int writeQueue,
    /** 줄에서 기다린 뒤 쓰기까지 끝나야 하는 시간. 넘으면 503 — 배치가 다시 보낸다. */
    @DefaultValue("60s") Duration writeWait
) {

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

}
