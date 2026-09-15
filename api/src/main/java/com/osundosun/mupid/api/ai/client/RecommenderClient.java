package com.osundosun.mupid.api.ai.client;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String FACETS = "/internal/facets";

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
                return new Attempt(null, false, true, "빈 응답");
            }
            return new Attempt(AiJson.read(body, RecommenderDto.Response.class), false, false, null);
        } catch (RestClientException e) {
            // 연결 실패·읽기 타임아웃. 규약상 재시도 대상이다.
            log.warn("추천 서버 호출 실패 path={}", path, e);
            return new Attempt(null, true, false, e.getClass().getSimpleName());
        }
    }

    private Attempt classify(String path, HttpStatusCode status, String errorCode) {
        if (NO_CANDIDATES.equals(errorCode)) {
            // 장애가 아니다. 화면에는 "맞는 곡이 없다" 로 나가야 한다.
            log.info("추천 결과 없음 path={}", path);
            return new Attempt(null, false, true, NO_CANDIDATES);
        }
        boolean retryable = status.is5xxServerError() && status.value() != 501;
        log.warn("추천 서버 오류 path={} status={} error_code={}", path, status, errorCode);
        return new Attempt(null, retryable, false, errorCode);
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
    private record Attempt(
        RecommenderDto.Response response,
        boolean retryable,
        boolean empty,
        String reason
    ) {
    }

}
