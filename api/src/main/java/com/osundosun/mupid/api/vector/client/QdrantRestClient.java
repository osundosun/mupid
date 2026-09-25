package com.osundosun.mupid.api.vector.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;
import com.osundosun.mupid.api.vector.schema.QdrantSchema;

import lombok.extern.slf4j.Slf4j;

/**
 * Qdrant REST 호출 (S15P21A204-457). {@link VectorPointStore} 의 운영 판이다.
 *
 * <p><b>SDK 를 쓰지 않는다.</b> 부르는 것이 여덟 개뿐이고(컬렉션 정보·벡터 덧붙이기·upsert·삭제 둘·
 * 벡터 갱신·조회·개수·스크롤) 모두 JSON 한 번이다. ai_server 의 {@code app/qdrant_store.py} 와 같은
 * 판단이다. gRPC SDK 는 네이티브 의존(netty·protobuf)을 끌고 온다.
 *
 * <p>{@code RestClient} 를 따로 둔다 — 추천 서버({@code recommenderRestClient}, 5초)와 타임아웃이 다르고,
 * {@code wait=true} 로 색인 반영까지 기다리므로 읽기 타임아웃이 길다. 사용자 요청이 이 연결을 쓰지
 * 않는다. 동시성은 {@code VectorWriteExecutor} 가 막는다.
 *
 * <p>JSON 은 자체 매퍼로 만든다(애플리케이션 매퍼는 SNAKE_CASE 이고 Jackson 3 다 — {@code AiJson} 참고).
 * 벡터는 {@code float[]} 그대로 싣는다. Jackson 은 float 를 다시 읽으면 같은 float 가 되는 가장 짧은
 * 표기로 쓴다 — Qdrant 가 f32 로 읽어 배치가 보낸 바이트와 같은 값이 된다.
 */
@Slf4j
@Component
public class QdrantRestClient implements VectorPointStore {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final String API_KEY_HEADER = "api-key";

    private final QdrantProperties properties;
    private final QdrantSchema schema;
    private final RestClient rest;

    public QdrantRestClient(QdrantProperties properties, QdrantSchema schema) {
        this.properties = properties;
        this.schema = schema;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        // 주소가 비어도 빈은 만든다. 서비스가 isConfigured() 로 먼저 막는다 — 여기서 던지면 벡터와
        // 무관한 기능까지 기동이 막힌다 (RecommenderConfig 와 같은 판단).
        this.rest = RestClient.builder()
            .baseUrl(properties.isConfigured() ? properties.url().strip() : "http://qdrant.invalid")
            .requestFactory(factory)
            .build();
    }

    private String base() {
        return "/collections/" + schema.collection();
    }

    @Override
    public Set<String> vectorNames() {
        JsonNode vectors = call("GET", base(), null)
            .path("config").path("params").path("vectors");
        Set<String> out = new HashSet<>();
        vectors.fieldNames().forEachRemaining(out::add);
        return out;
    }

    @Override
    public void addVector(String name, Map<String, Object> params) {
        // Qdrant v1.19 의 named vector 덧붙이기. 456 도구(tools/qdrant_collection.py ensure)와 같은 본문이다.
        call("PUT", base() + "/vectors/" + name + "?wait=true", Map.of("dense", params));
    }

    @Override
    public void upsert(List<Point> points) {
        List<Map<String, Object>> body = new ArrayList<>(points.size());
        for (Point point : points) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", point.id().toString());
            one.put("vector", point.vectors());
            one.put("payload", point.payload());
            body.add(one);
        }
        call("PUT", base() + "/points?wait=true", Map.of("points", body));
    }

    @Override
    public void deleteTrackPointsExcept(int trackId, Collection<UUID> keep) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", List.of(trackMatch(trackId)));
        if (!keep.isEmpty()) {
            filter.put("must_not", List.of(Map.of("has_id", ids(keep))));
        }
        call("POST", base() + "/points/delete?wait=true", Map.of("filter", filter));
    }

    @Override
    public void deleteTracks(Collection<Integer> trackIds) {
        if (trackIds.isEmpty()) {
            return;
        }
        call("POST", base() + "/points/delete?wait=true", Map.of("filter", Map.of("must", List.of(
            Map.of("key", "track_id", "match", Map.of("any", List.copyOf(trackIds)))))));
    }

    @Override
    public void updateVectors(Map<UUID, Map<String, float[]>> vectors) {
        List<Map<String, Object>> body = new ArrayList<>(vectors.size());
        vectors.forEach((id, named) -> body.add(Map.of("id", id.toString(), "vector", named)));
        call("PUT", base() + "/points/vectors?wait=true", Map.of("points", body));
    }

    @Override
    public Set<UUID> existing(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        JsonNode got = call("POST", base() + "/points",
            Map.of("ids", ids(ids), "with_payload", false, "with_vector", false));
        Set<UUID> out = new HashSet<>();
        got.forEach(point -> out.add(UUID.fromString(point.path("id").asText())));
        return out;
    }

    @Override
    public long countWithVector(String name) {
        return call("POST", base() + "/points/count", Map.of("exact", true,
            "filter", Map.of("must", List.of(Map.of("has_vector", name)))))
            .path("count").asLong();
    }

    @Override
    public Page scroll(Object offset, int limit, List<String> payloadFields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("limit", limit);
        body.put("with_payload", payloadFields);
        body.put("with_vector", false);
        if (offset != null) {
            body.put("offset", offset);
        }
        JsonNode got = call("POST", base() + "/points/scroll", body);
        List<StoredPoint> points = new ArrayList<>();
        for (JsonNode point : got.path("points")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = JSON.convertValue(point.path("payload"), Map.class);
            points.add(new StoredPoint(UUID.fromString(point.path("id").asText()), payload));
        }
        JsonNode next = got.path("next_page_offset");
        return new Page(points, next.isMissingNode() || next.isNull() ? null : next.asText());
    }

    private static Map<String, Object> trackMatch(int trackId) {
        return Map.of("key", "track_id", "match", Map.of("value", trackId));
    }

    private static List<String> ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /**
     * REST 한 번. 응답의 {@code result} 를 준다.
     *
     * <p>컬렉션 404 는 "만들지 않았다" 라 따로 알린다(운영 절차 — 456 도구). 포인트가 없는 404 는
     * 부르는 쪽({@code existing})이 미리 걸러 여기까지 오지 않는다. 나머지는 모두 "다시 보내라" 다.
     * 로그에는 경로와 Qdrant 의 사유만 싣는다 — 본문(벡터)과 키는 싣지 않는다.
     */
    private JsonNode call(String method, String path, Object body) {
        if (!properties.isConfigured()) {
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_NOT_CONFIGURED);
        }
        byte[] bytes;
        try {
            bytes = body == null ? null : JSON.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant 요청 본문을 만들지 못했다", e);
        }
        try {
            RestClient.RequestBodySpec spec = rest.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON);
            if (properties.hasApiKey()) {
                spec.header(API_KEY_HEADER, properties.apiKey());
            }
            if (bytes != null) {
                spec.body(bytes);
            }
            byte[] response = spec.exchange((request, res) -> {
                HttpStatusCode status = res.getStatusCode();
                byte[] raw = res.getBody().readAllBytes();
                if (status.is2xxSuccessful()) {
                    return raw;
                }
                String why = reason(raw);
                if (status.value() == 404 && why.contains("Collection")) {
                    log.error("Qdrant 컬렉션 {} 이 없다 — 운영 절차로 만든다(python -m ai_server.tools.qdrant_collection)",
                        schema.collection());
                    throw new BusinessException(VectorErrorCode.VECTOR_COLLECTION_MISSING);
                }
                log.error("Qdrant {} {} → HTTP {}: {}", method, path, status.value(), why);
                throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
            });
            return JSON.readTree(response).path("result");
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException | IOException e) {
            log.error("Qdrant {} {} 에 닿지 못했다: {}", method, path, e.toString());
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        }
    }

    private static String reason(byte[] raw) {
        try {
            String why = JSON.readTree(raw).path("status").path("error").asText("");
            return why.length() > 300 ? why.substring(0, 300) : why;
        } catch (IOException e) {
            return "";
        }
    }

}
