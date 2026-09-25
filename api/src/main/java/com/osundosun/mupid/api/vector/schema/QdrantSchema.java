package com.osundosun.mupid.api.vector.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;

/**
 * Qdrant 순간 벡터 컬렉션의 정의 (S15P21A204-457, 설계 §6.1).
 *
 * <p><b>정본은 {@code ai_server/app/qdrant_collection.json} 이고, 이 리소스는 그 바이트 사본이다.</b>
 * api 이미지는 {@code api/} 만 빌드 문맥으로 받아 저장소의 다른 폴더를 읽을 수 없어 복사해 둔다.
 * 두 파일이 한 바이트라도 다르면 {@code ai_server/tests/test_qdrant_schema_copy.py} 가 CI 에서 멈춘다 —
 * 값을 두 언어가 각자 적으면 한쪽만 고친 날 차원·포인트 id 가 갈리고, 그것은 오류가 아니라
 * "검색은 되는데 엉뚱한 구간" 으로만 드러난다(456 {@code qdrant_schema.py} 머리말).
 *
 * <h2>포인트 id — UUIDv5</h2>
 *
 * <p>자바에는 v5 가 없다({@link UUID#nameUUIDFromBytes} 는 MD5 의 v3). RFC 4122 대로 직접 만든다 —
 * SHA-1(namespace 16바이트 ‖ UTF-8 이름) 앞 16바이트에 version 5·variant 비트를 얹는다.
 * 파이썬 {@code uuid.uuid5} 와 같은 값이다(대조값 {@code T000001#3 → 08155de7-…}, {@code QdrantSchemaTest}).
 */
@Component
public class QdrantSchema {

    public static final String RESOURCE = "qdrant/qdrant_collection.json";

    /** 원본 복원 {@code Z = μ + r·u} 의 방향. ANN 대상이다. */
    public static final String MERT = "mert";
    public static final String CLAP = "clap";
    public static final String FEAT = "feat_v1";

    /**
     * 모델 버전 표기. {@code models.lock} 의 {@code stage1.version}·배치용 Object Storage API 와 같은 규칙
     * ({@code v3}). 벡터 이름에 그대로 들어가므로 다른 글자가 섞이면 이름이 새로 생긴다.
     */
    private static final Pattern MODEL_VERSION = Pattern.compile("^v[0-9]{1,6}$");

    private final JsonNode root;
    private final String collection;
    private final UUID namespace;

    @Autowired
    public QdrantSchema(QdrantProperties properties) {
        this(load(), properties.collection());
    }

    QdrantSchema(JsonNode root, String collectionOverride) {
        this.root = root;
        this.collection = collectionOverride == null || collectionOverride.isBlank()
            ? root.path("collection").asText() : collectionOverride.strip();
        this.namespace = UUID.fromString(root.path("point_id").path("namespace").asText());
    }

    /** 시험용 — 리소스를 그대로 읽는다. */
    public static QdrantSchema fromResource() {
        return new QdrantSchema(load(), null);
    }

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Qdrant 컬렉션 정의를 읽지 못했다: " + RESOURCE, e);
        }
    }

    public String collection() {
        return collection;
    }

    public String embeddingVersion() {
        return root.path("embedding_version").asText();
    }

    /** 고정 named vector({@code mert}·{@code clap}·{@code feat_v1})의 차원. 없는 이름이면 -1. */
    public int dim(String vector) {
        JsonNode node = root.path("vectors").path(vector);
        return node.isMissingNode() ? -1 : node.path("size").asInt(-1);
    }

    public int modelDim() {
        return root.path("model_vector").path("params").path("size").asInt();
    }

    public static boolean isModelVersion(String version) {
        return version != null && MODEL_VERSION.matcher(version).matches();
    }

    /** 모델 버전 → 벡터 이름. {@code v3} → {@code stage1_v3}. */
    public String modelVector(String version) {
        if (!isModelVersion(version)) {
            throw new IllegalArgumentException("모델 버전은 v<숫자> 여야 한다: " + version);
        }
        return root.path("model_vector").path("name").asText().replace("{version}", version);
    }

    /**
     * 새 모델 벡터를 덧붙이는 본문의 값({@code {"size":128,"distance":"Cosine"}}). {@code _} 로 시작하는
     * 설명 키는 뺀다 — Qdrant 는 모르는 키를 거절한다.
     */
    public Map<String, Object> modelVectorParams() {
        return strip(root.path("model_vector").path("params"));
    }

    public UUID pointId(String externalId) {
        return uuid5(namespace, externalId);
    }

    static UUID uuid5(UUID namespace, String name) {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer ns = ByteBuffer.allocate(16);
        ns.putLong(namespace.getMostSignificantBits());
        ns.putLong(namespace.getLeastSignificantBits());
        sha1.update(ns.array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);   // version 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);   // variant RFC 4122
        ByteBuffer out = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(out.getLong(), out.getLong());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> strip(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        Iterator<String> names = copy.fieldNames();
        while (names.hasNext()) {
            if (names.next().startsWith("_")) {
                names.remove();
            }
        }
        return new LinkedHashMap<>(new ObjectMapper().convertValue(copy, Map.class));
    }

}
