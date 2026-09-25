package com.osundosun.mupid.api.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 배치용 Object Storage API(S15P21A204-453)를 HTTP 로 끝까지 지나가며 확인한다.
 *
 * <p>② 업로드는 원래 Nginx 가 한다 — {@code auth_request} 로 허가를 묻고 본문을 Filer 로
 * 흘린다. 여기엔 Nginx 가 없으므로 허가 응답의 경로를 확인한 뒤 <b>같은 오브젝트를 S3 로
 * 직접 올려</b> 그 자리를 대신한다. ③ 완료는 진짜 스토리지를 흘려 읽어 sha256 을 잰다 —
 * 대역이 아니라서 "서버가 정말 바이트를 대조하는가" 가 여기서 드러난다.
 *
 * <p>DB 는 테스트마다 롤백되지만 스토리지 오브젝트는 남는다. {@code @AfterEach} 가 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SystemObjectApiTest {

    private static final String OBJECTS = "/api/v1/system/objects";

    @Autowired
    private MockMvc mockMvc;

    /** 요청 본문은 키를 snake_case 로 직접 적는다. 앱의 매퍼 설정에 기대지 않는다 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProps;

    @Autowired
    private StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    private User ci;
    private User member;
    private User admin;

    /** 테스트마다 새 모델 이름 — 지난 실행이 남긴 오브젝트와 겹치지 않게 */
    private String model;

    private final List<String> uploadedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ci = persistUser(UserRole.ROLE_SYSTEM);
        member = persistUser(UserRole.ROLE_USER);
        admin = persistUser(UserRole.ROLE_ADMIN);
        model = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    @AfterEach
    void cleanUp() {
        uploadedKeys.forEach(storageService::deleteObjectQuietly);
    }

    // ─────────────────────────────────────────────────────────────
    // 3단계
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("요청 → 업로드 허가 → 완료를 지나면 COMPLETED 가 되고 받기가 Filer 로 돌린다")
    void threeStepsThenDownload() throws Exception {
        byte[] bytes = "{\"version\":\"v1\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = Map.of("manifest.json", bytes);

        JsonNode created = requestObject(ci, "v1", "manifest.json", bytes, files.keySet());
        int objectId = created.path("object_id").asInt();
        assertEquals("UPLOADING", created.path("status").asText());
        assertEquals("/upload/system/" + objectId, created.path("upload_url").asText());
        assertEquals("UPLOADING", created.path("model_status").asText());

        String key = "system/model/%s/v1/manifest.json".formatted(model);
        String filerPath = "/buckets/%s/%s".formatted(storageProps.bucketName(), key);

        // ② Nginx 가 묻는 그대로 — 본문 없이, 원래 길이만 헤더로
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", objectId)
                .header("X-Original-Content-Length", bytes.length)))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Storage-Path", filerPath));
        put(key, bytes);

        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", objectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.upload_url").doesNotExist())
            .andExpect(jsonPath("$.data.model_status").value("CANDIDATE"));

        // 받기 두 길 — 번호로, 이름·버전·파일명으로
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}", objectId)))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Redirect", "/storage" + filerPath))
            .andExpect(header().string("Content-Type", "application/json"));
        mockMvc.perform(as(ci, get("/api/v1/system/models/{name}/v1/files/manifest.json", model)))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Accel-Redirect", "/storage" + filerPath));

        // 완료된 것은 다시 올릴 수 없다 — Filer 는 같은 경로 PUT 을 그냥 덮어쓴다
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", objectId)
                .header("X-Original-Content-Length", bytes.length)))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Mupid-Error", "MUPID_OBJECT_ALREADY_COMPLETED"));
    }

    @Test
    @DisplayName("업로드 허가는 길이 없는 요청과 신고와 다른 길이를 거절한다")
    void uploadAuthChecksLength() throws Exception {
        byte[] bytes = bytes("abc");
        int objectId = requestObject(ci, "v1", "manifest.json", bytes, List.of("manifest.json"))
            .path("object_id").asInt();

        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", objectId)))
            .andExpect(status().isLengthRequired())
            .andExpect(header().string("X-Mupid-Error", "MUPID_UPLOAD_LENGTH_REQUIRED"));
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", objectId)
                .header("X-Original-Content-Length", bytes.length + 1)))
            .andExpect(status().isConflict())
            .andExpect(header().string("X-Mupid-Error", "MUPID_UPLOAD_SIZE_MISMATCH"));
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", 999_999_999)
                .header("X-Original-Content-Length", 1)))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("완료: 스토리지의 sha256 이 신고와 다르면 거절하고 UPLOADING 으로 남긴다")
    void completeRejectsShaMismatch() throws Exception {
        byte[] declared = bytes("the real manifest");
        byte[] tampered = bytes("the fake manifest");  // 길이가 같다 — 크기로는 못 잡는다
        assertEquals(declared.length, tampered.length);

        int objectId = requestObject(ci, "v1", "manifest.json", declared, List.of("manifest.json"))
            .path("object_id").asInt();
        String key = "system/model/%s/v1/manifest.json".formatted(model);

        put(key, tampered);
        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", objectId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_SHA256_MISMATCH"));
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}", objectId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_NOT_COMPLETED"));

        // 올린 쪽이 ② 부터 다시 하면 된다
        mockMvc.perform(as(ci, get(OBJECTS + "/{id}/upload-auth", objectId)
                .header("X-Original-Content-Length", declared.length)))
            .andExpect(status().isOk());
        put(key, declared);
        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", objectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("완료: 올라오지 않았거나 크기가 다르면 거절한다")
    void completeRejectsMissingOrResized() throws Exception {
        byte[] declared = bytes("0123456789");
        int objectId = requestObject(ci, "v1", "manifest.json", declared, List.of("manifest.json"))
            .path("object_id").asInt();

        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", objectId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_NOT_UPLOADED"));

        put("system/model/%s/v1/manifest.json".formatted(model), bytes("01234"));
        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", objectId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_SIZE_MISMATCH"));
    }

    // ─────────────────────────────────────────────────────────────
    // 불변 · 멱등
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 이름·버전·파일명에 다른 sha256 은 거절하고, 같은 sha256 이면 같은 오브젝트를 돌려준다")
    void sameVersionIsImmutable() throws Exception {
        byte[] bytes = bytes("weights");
        List<String> files = List.of("manifest.json");
        int first = requestObject(ci, "v1", "manifest.json", bytes, files).path("object_id").asInt();

        // 같은 내용 — 멱등
        int again = requestObject(ci, "v1", "manifest.json", bytes, files).path("object_id").asInt();
        assertEquals(first, again);

        // 다른 내용 — 거절 (완료 전이어도)
        mockMvc.perform(as(ci, post(OBJECTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("v1", "manifest.json", bytes("other!!"), files)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_IMMUTABLE"));

        // 완료 뒤에도 같다. 완료된 것은 ①이 COMPLETED 로 바로 알려 준다
        put("system/model/%s/v1/manifest.json".formatted(model), bytes);
        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", first))).andExpect(status().isOk());
        JsonNode completed = requestObject(ci, "v1", "manifest.json", bytes, files);
        assertEquals(first, completed.path("object_id").asInt());
        assertEquals("COMPLETED", completed.path("status").asText());
        mockMvc.perform(as(ci, post(OBJECTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("v1", "manifest.json", bytes("other!!"), files)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_OBJECT_IMMUTABLE"));
    }

    @Test
    @DisplayName("같은 버전에 다른 파일 목록을 싣거나, 목록 밖 파일·금지 확장자·manifest 없는 목록은 거절한다")
    void versionFileListIsFixedAndChecked() throws Exception {
        byte[] bytes = bytes("x");
        requestObject(ci, "v1", "manifest.json", bytes, List.of("manifest.json", "mu.npy"));

        // 순서만 다른 것은 같은 목록이다
        requestObject(ci, "v1", "mu.npy", bytes, List.of("mu.npy", "manifest.json"));

        expectRejected("v1", "mu.npy", List.of("manifest.json", "mu.npy", "pca.npz"),
            409, "MUPID_MODEL_VERSION_FILES_MISMATCH");
        expectRejected("v2", "pca.npz", List.of("manifest.json", "mu.npy"),
            400, "MUPID_OBJECT_FILE_NOT_DECLARED");
        expectRejected("v2", "stage1.pt", List.of("manifest.json", "stage1.pt"),
            400, "MUPID_INVALID_OBJECT_FILE_NAME");
        expectRejected("v2", "mu.npy", List.of("mu.npy"),
            400, "MUPID_MANIFEST_REQUIRED");
        expectRejected("3", "manifest.json", List.of("manifest.json"),
            400, "MUPID_INVALID_MODEL_VERSION");

        mockMvc.perform(as(ci, post(OBJECTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "kind", "VECTOR", "name", model, "version", "v1", "file_name", "manifest.json",
                    "size_bytes", 1, "sha256", sha256(bytes), "version_files", List.of("manifest.json")))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_UNSUPPORTED_OBJECT_KIND"));
    }

    // ─────────────────────────────────────────────────────────────
    // model_versions
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("선언한 파일이 모두 완료돼야 CANDIDATE 가 되고, manifest 의 sha256 이 버전에 남는다")
    void versionBecomesCandidateWhenAllFilesComplete() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", bytes("{\"files\":3}"));
        files.put("user_encoder.npz", bytes("encoder-weights"));
        files.put("combiner.json", bytes("{\"w\":[1,2]}"));

        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<String, byte[]> it : files.entrySet()) {
            ids.add(requestObject(ci, "v1", it.getKey(), it.getValue(), files.keySet()).path("object_id").asInt());
        }
        int i = 0;
        for (Map.Entry<String, byte[]> it : files.entrySet()) {
            put("system/model/%s/v1/%s".formatted(model, it.getKey()), it.getValue());
            boolean last = ++i == files.size();
            mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", ids.get(i - 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model_status").value(last ? "CANDIDATE" : "UPLOADING"));
        }

        mockMvc.perform(as(ci, get("/api/v1/system/models/{name}", model)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.current_version").doesNotExist())
            .andExpect(jsonPath("$.data.versions[0].version").value("v1"))
            .andExpect(jsonPath("$.data.versions[0].status").value("CANDIDATE"))
            .andExpect(jsonPath("$.data.versions[0].manifest_sha256").value(sha256(files.get("manifest.json"))))
            .andExpect(jsonPath("$.data.versions[0].declared_file_count").value(3))
            .andExpect(jsonPath("$.data.versions[0].files.length()").value(3))
            .andExpect(jsonPath("$.data.versions[0].files[0].file_name").value("combiner.json"));
    }

    @Test
    @DisplayName("채택·폐기: 후보만 채택되고, 이전 채택은 남으며, 지금 쓰는 버전은 폐기할 수 없다")
    void adoptAndRetireTransitions() throws Exception {
        String models = "/api/v1/system/models/{name}/{version}/";

        // 파일이 다 모이지 않은 버전은 채택할 수 없다
        requestObject(ci, "v3", "manifest.json", bytes("v3"), List.of("manifest.json"));
        mockMvc.perform(as(ci, post(models + "adopt", model, "v3")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_MODEL_VERSION_NOT_READY"));

        uploadCandidate("v1");
        uploadCandidate("v2");

        mockMvc.perform(as(ci, post(models + "adopt", model, "v1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("ADOPTED"))
            .andExpect(jsonPath("$.data.current_version").value("v1"));
        mockMvc.perform(as(ci, post(models + "adopt", model, "v2")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.current_version").value("v2"));

        // 이전 채택 버전은 그대로 ADOPTED — 폐기는 사람이 한다
        mockMvc.perform(as(ci, get("/api/v1/system/models/{name}", model)))
            .andExpect(jsonPath("$.data.current_version").value("v2"))
            .andExpect(jsonPath("$.data.versions[?(@.version == 'v1')].status").value("ADOPTED"));

        // 지금 쓰는 버전은 폐기할 수 없다
        mockMvc.perform(as(admin, post(models + "retire", model, "v2")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_MODEL_VERSION_IN_SERVICE"));

        // 사람(ADMIN)이 이전 버전을 폐기한다. 다시 불러도 같다(멱등)
        mockMvc.perform(as(admin, post(models + "retire", model, "v1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RETIRED"))
            .andExpect(jsonPath("$.data.current_version").value("v2"));
        mockMvc.perform(as(ci, post(models + "retire", model, "v1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("RETIRED"));

        // 폐기된 버전은 다시 채택할 수 없다. 파일은 계속 받을 수 있다 (되돌리기용)
        mockMvc.perform(as(ci, post(models + "adopt", model, "v1")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("MUPID_MODEL_VERSION_RETIRED"));
        mockMvc.perform(as(ci, get("/api/v1/system/models/{name}/v1/files/manifest.json", model)))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Accel-Redirect"));

        mockMvc.perform(as(ci, post(models + "adopt", model, "v9")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MUPID_MODEL_VERSION_NOT_FOUND"));
        mockMvc.perform(as(ci, get("/api/v1/system/models/{name}", "nosuchmodel")))
            .andExpect(status().isNotFound());
    }

    // ─────────────────────────────────────────────────────────────
    // 권한
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ROLE_SYSTEM(또는 ADMIN)이 아니면 모든 경로가 403 이다")
    void everyEndpointRequiresSystemRole() throws Exception {
        byte[] bytes = bytes("x");
        int objectId = requestObject(ci, "v1", "manifest.json", bytes, List.of("manifest.json"))
            .path("object_id").asInt();

        List<MockHttpServletRequestBuilder> requests = List.of(
            post(OBJECTS).contentType(MediaType.APPLICATION_JSON)
                .content(body("v2", "manifest.json", bytes, List.of("manifest.json"))),
            get(OBJECTS + "/{id}/upload-auth", objectId).header("X-Original-Content-Length", 1),
            post(OBJECTS + "/{id}/complete", objectId),
            get(OBJECTS + "/{id}", objectId),
            get("/api/v1/system/models/{name}", model),
            get("/api/v1/system/models/{name}/v1/files/manifest.json", model),
            post("/api/v1/system/models/{name}/v1/adopt", model),
            post("/api/v1/system/models/{name}/v1/retire", model));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(as(member, request))
                .andExpect(status().isForbidden());
        }
        // 토큰이 없으면 401 — Nginx auth_request 가 그대로 넘기는 두 상태 중 하나다
        mockMvc.perform(get(OBJECTS + "/{id}/upload-auth", objectId).header("X-Original-Content-Length", 1))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────

    /** manifest.json 하나짜리 버전을 후보까지 올린다 */
    private void uploadCandidate(String version) throws Exception {
        byte[] bytes = bytes("manifest " + version);
        int id = requestObject(ci, version, "manifest.json", bytes, List.of("manifest.json"))
            .path("object_id").asInt();
        put("system/model/%s/%s/manifest.json".formatted(model, version), bytes);
        mockMvc.perform(as(ci, post(OBJECTS + "/{id}/complete", id)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.model_status").value("CANDIDATE"));
    }

    private JsonNode requestObject(
        User actor, String version, String fileName, byte[] bytes, Collection<String> versionFiles
    ) throws Exception {
        String response = mockMvc.perform(as(actor, post(OBJECTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(version, fileName, bytes, versionFiles)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = objectMapper.readTree(response).path("data");
        assertNotNull(data.get("object_id"));
        return data;
    }

    private void expectRejected(
        String version, String fileName, List<String> versionFiles, int status, String code
    ) throws Exception {
        mockMvc.perform(as(ci, post(OBJECTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(version, fileName, bytes("x"), versionFiles)))
            .andExpect(status().is(status))
            .andExpect(jsonPath("$.code").value(code));
    }

    private String body(String version, String fileName, byte[] bytes, Collection<String> versionFiles)
        throws Exception {
        return objectMapper.writeValueAsString(Map.of(
            "kind", "MODEL",
            "name", model,
            "version", version,
            "file_name", fileName,
            "size_bytes", bytes.length,
            "sha256", sha256(bytes),
            "version_files", List.copyOf(versionFiles)));
    }

    /** ② 에서 Nginx 가 할 일 — 같은 버킷·같은 키에 바이트를 올린다 */
    private void put(String key, byte[] bytes) {
        s3Client.putObject(
            PutObjectRequest.builder().bucket(storageProps.bucketName()).key(key).build(),
            RequestBody.fromBytes(bytes));
        uploadedKeys.add(key);
    }

    private static MockHttpServletRequestBuilder as(User actor, MockHttpServletRequestBuilder builder) {
        return builder
            .with(csrf())
            .with(authentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority(actor.getRole().name())))));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private User persistUser(UserRole role) {
        User user = User.builder()
            .email("system-object-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("검증용")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(role)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        em.flush();
        return user;
    }

}
