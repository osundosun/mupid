package com.osundosun.mupid.api.ai.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.properties.RecommenderProperties;
import com.osundosun.mupid.api.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 추천 서버(`ai_server/`) 한 방 호출.
 *
 * <p>대화의 상태도, 곡 메타데이터 채우기도 여기서는 모른다. "요청을 넣으면 구간 목록이
 * 나온다" 만 책임진다. {@link OpenAiClient} 와 같은 자리다.
 *
 * <h2>실패를 어떻게 나누는가</h2>
 *
 * <p>상태 코드만 보면 안 된다. 404 가 둘 다 있기 때문이다.
 *
 * <ul>
 *   <li>{@code NO_CANDIDATES} (404) — **장애가 아니다.** 조건에 맞는 곡이 없다는 뜻이라
 *       빈 목록으로 돌려준다. 재시도하면 사용자만 기다린다
 *   <li>{@code SEARCH_NOT_CONFIGURED} (501) — 추천 서버에 해석기가 없다. 우리는
 *       {@code query_plan} 을 보내므로 여기 걸리면 안 된다. 걸렸다면 우리 쪽 실수다
 *   <li>{@code INDEX_NOT_READY} (503) · 500 · 타임아웃 — 재시도 대상. 한 번만 더 부른다
 *   <li>400·그 밖의 404 — 다시 불러도 같은 답이다. 바로 올린다
 * </ul>
 *
 * <p>재시도가 한 번인 이유는 읽기 타임아웃이 5초라 두 번이면 최악 10초를 기다리기
 * 때문이다. 대화 응답 안에서 이미 LLM 을 기다린 뒤라 그 위에 더 얹을 수 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommenderClient {

    private static final String SEARCH = "/internal/recommendations/search";
    private static final String NEXT = "/internal/recommendations/next";
    private static final String DAILY = "/internal/recommendations/daily";
    private static final String SIMILAR = "/internal/similar";
    private static final String FACETS = "/internal/facets";

    /** 추천 서버가 `/internal` 을 지킬 때 쓰는 공유 비밀 헤더. `/health` 는 검사 밖이다. */
    private static final String AUTH_HEADER = "X-Internal-Token";

    /** 조건에 맞는 곡이 없다. 빈 목록으로 답한다. */
    private static final String NO_CANDIDATES = "NO_CANDIDATES";

    private final RestClient recommenderRestClient;
    private final RecommenderProperties props;

    /** 카탈로그는 기동 후 바뀌지 않는다. 대화마다 다시 받을 이유가 없다. */
    private final AtomicReference<RecommenderDto.Facets> facetsCache = new AtomicReference<>();

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * 조건 기반 추천. {@code queryPlan} 은 `/internal/facets` 의 어휘로 만든다.
     */
    public List<RecommenderDto.Item> search(RecommenderDto.SearchRequest request) {
        return items(SEARCH, request);
    }

    /** 지금 듣는 곡을 기준으로 이어 듣기. 조건이 없을 때 쓴다. */
    public List<RecommenderDto.Item> next(RecommenderDto.NextRequest request) {
        return items(NEXT, request);
    }

    /** 오늘의 모먼트. 배치가 사용자마다 한 번씩 부른다. */
    public List<RecommenderDto.Item> daily(RecommenderDto.DailyRequest request) {
        return items(DAILY, request);
    }

    /**
     * 기준 구간과 닮은 다른 곡의 구간. **개인화가 없다.**
     *
     * <p>응답 모양이 추천과 달라(`similarity`·`embedding_version`) 공용 경로를 쓰지
     * 않는다. 대신 실패 분류는 같은 규칙을 탄다.
     */
    public List<RecommenderDto.SimilarItem> similar(RecommenderDto.SimilarRequest request) {
        if (!isConfigured()) {
            log.warn("추천 서버 주소가 없다 (MUPID_RECOMMENDER_BASE_URL)");
            throw new BusinessException(AiErrorCode.RECOMMENDER_NOT_CONFIGURED);
        }
        Attempt first = callSimilar(request);
        if (first.retryable() && props.retryOnce()) {
            log.warn("추천 서버 재시도 path={} 사유={}", SIMILAR, first.reason());
            first = callSimilar(request);
        }
        if (first.similar() != null) {
            return first.similar().safeItems();
        }
        if (first.empty()) {
            return List.of();
        }
        throw new BusinessException(first.retryable()
            ? AiErrorCode.RECOMMENDER_UNAVAILABLE
            : AiErrorCode.RECOMMENDER_REQUEST_FAILED);
    }

    /**
     * 쓸 수 있는 태그 어휘. 실패하면 {@code null} 이다 — 어휘 없이도 대화는 되어야 하므로
     * 여기서는 예외를 올리지 않는다. 대신 툴 스키마에 값 목록이 빠진다.
     */
    public RecommenderDto.Facets facets() {
        RecommenderDto.Facets cached = facetsCache.get();
        if (cached != null) {
            return cached;
        }
        if (!isConfigured()) {
            return null;
        }
        try {
            byte[] body = recommenderRestClient.get()
                .uri(FACETS)
                .accept(MediaType.APPLICATION_JSON)
                .headers(this::authorize)
                .retrieve()
                .body(byte[].class);
            if (body == null || body.length == 0) {
                return null;
            }
            RecommenderDto.Facets facets = AiJson.read(body, RecommenderDto.Facets.class);
            facetsCache.set(facets);
            return facets;
        } catch (RuntimeException e) {
            // 색인이 아직 안 올라왔을 수 있다. 다음 호출에서 다시 받는다 (캐시에 넣지 않는다).
            log.warn("추천 서버 태그 어휘를 받지 못했다 — 조건 값 목록 없이 진행한다", e);
            return null;
        }
    }

    private List<RecommenderDto.Item> items(String path, Object request) {
        if (!isConfigured()) {
            log.warn("추천 서버 주소가 없다 (MUPID_RECOMMENDER_BASE_URL)");
            throw new BusinessException(AiErrorCode.RECOMMENDER_NOT_CONFIGURED);
        }
        Attempt first = call(path, request);
        if (first.retryable() && props.retryOnce()) {
            log.warn("추천 서버 재시도 path={} 사유={}", path, first.reason());
            first = call(path, request);
        }
        if (first.response() != null) {
            return first.response().safeItems();
        }
        if (first.empty()) {
            return List.of();
        }
        throw new BusinessException(first.retryable()
            ? AiErrorCode.RECOMMENDER_UNAVAILABLE
            : AiErrorCode.RECOMMENDER_REQUEST_FAILED);
    }

    private Attempt call(String path, Object request) {
        try {
            AtomicReference<Attempt> failure = new AtomicReference<>();
            byte[] body = recommenderRestClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(this::authorize)
                .body(AiJson.writeBytes(request))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    failure.set(classify(path, res.getStatusCode(), readError(res)));
                })
                .body(byte[].class);
            Attempt failed = failure.get();
            if (failed != null) {
                return failed;
            }
            if (body == null || body.length == 0) {
                return Attempt.none("빈 응답");
            }
            return Attempt.ok(AiJson.read(body, RecommenderDto.Response.class));
        } catch (RestClientException e) {
            // 연결 실패·읽기 타임아웃. 규약상 재시도 대상이다.
            log.warn("추천 서버 호출 실패 path={}", path, e);
            return Attempt.failed(true, e.getClass().getSimpleName());
        }
    }

    /**
     * 공유 비밀이 설정돼 있으면 헤더로 싣는다. 값은 여기서만 만지고 로그에 남기지 않는다.
     */
    private void authorize(HttpHeaders headers) {
        if (props.hasInternalToken()) {
            headers.set(AUTH_HEADER, props.internalToken());
        }
    }

    private Attempt classify(String path, HttpStatusCode status, String errorCode) {
        if (NO_CANDIDATES.equals(errorCode)) {
            // 장애가 아니다. 화면에는 "맞는 곡이 없다" 로 나가야 한다.
            log.info("추천 결과 없음 path={}", path);
            return Attempt.none(NO_CANDIDATES);
        }
        if (status.value() == 401) {
            // 한쪽만 켠 상태다. 다시 불러도 같으므로 재시도하지 않는다.
            log.error("추천 서버가 인증을 거부했다 — MUPID_RECOMMENDER_INTERNAL_TOKEN 과 "
                + "추천 서버의 MUPID_AI_INTERNAL_TOKEN 이 같은지 본다 (설정 여부={})",
                props.hasInternalToken());
            return Attempt.failed(false, "UNAUTHORIZED");
        }
        boolean retryable = status.is5xxServerError() && status.value() != 501;
        log.warn("추천 서버 오류 path={} status={} error_code={}", path, status, errorCode);
        return Attempt.failed(retryable, errorCode);
    }

    /** 오류 본문에서 `error_code` 만 꺼낸다. 없으면 null — 그 자체가 정보다. */
    private String readError(ClientHttpResponse response) {
        try {
            byte[] raw = response.getBody().readAllBytes();
            if (raw.length == 0) {
                return null;
            }
            RecommenderDto.Error error = AiJson.read(raw, RecommenderDto.Error.class);
            return error == null ? null : error.errorCode();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param response  성공 응답. 실패면 null
     * @param retryable 한 번 더 불러 볼 만한가 (500·503·타임아웃)
     * @param empty     실패는 아니지만 결과가 없다 ({@code NO_CANDIDATES})
     */
    /**
     * 한 번의 호출 결과.
     *
     * <p>{@code response} 와 {@code similar} 중 하나만 찬다 — 두 경로의 응답 모양이
     * 다르기 때문이다. 둘 다 비어 있으면 {@code empty}(결과 없음) 또는 실패다.
     */
    private record Attempt(
        RecommenderDto.Response response,
        RecommenderDto.SimilarResponse similar,
        boolean retryable,
        boolean empty,
        String reason
    ) {

        static Attempt ok(RecommenderDto.Response response) {
            return new Attempt(response, null, false, false, null);
        }

        static Attempt ok(RecommenderDto.SimilarResponse similar) {
            return new Attempt(null, similar, false, false, null);
        }

        static Attempt none(String reason) {
            return new Attempt(null, null, false, true, reason);
        }

        static Attempt failed(boolean retryable, String reason) {
            return new Attempt(null, null, retryable, false, reason);
        }
    }

    /**
     * 닮은 구간은 응답 모양이 달라 따로 부른다. 실패 분류(`classify`)는 공유한다.
     */
    private Attempt callSimilar(RecommenderDto.SimilarRequest request) {
        try {
            AtomicReference<Attempt> failure = new AtomicReference<>();
            byte[] body = recommenderRestClient.post()
                .uri(SIMILAR)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers(this::authorize)
                .body(AiJson.writeBytes(request))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    failure.set(classify(SIMILAR, res.getStatusCode(), readError(res)));
                })
                .body(byte[].class);
            Attempt failed = failure.get();
            if (failed != null) {
                return failed;
            }
            if (body == null || body.length == 0) {
                return Attempt.none("빈 응답");
            }
            return Attempt.ok(AiJson.read(body, RecommenderDto.SimilarResponse.class));
        } catch (RestClientException e) {
            log.warn("추천 서버 호출 실패 path={}", SIMILAR, e);
            return Attempt.failed(true, e.getClass().getSimpleName());
        }
    }

}
