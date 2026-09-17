package com.osundosun.mupid.api.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.repository.MubtiRepository;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 이미지 저장소의 접근제한을 HTTP 로 끝까지 지나가며 확인한다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * 한때 {@code PROFILE} 에만 소유자 검사가 있었다. 그래서 남의 번호로는 프로필을 올릴 수
 * 없었지만, {@code ALBUM}·{@code ARTIST}·{@code TRACK}·{@code MUBTI} 는 <b>로그인만 하면
 * 아무나</b> 덮어쓰고 지울 수 있었다. 지금은 권한 판단이 카테고리가 들고 있는 정책 하나
 * ({@code FileCategory.accessPolicy})에 모여 있고, 업로드와 삭제가 <b>같은 검사</b>를
 * 지난다. 이 테스트는 그 대칭이 다시 깨지지 않게 못박는다.
 *
 * <h2>조회를 번호로 바꾼 뒤 무엇이 달라졌나</h2>
 *
 * {@code SecurityConfig} 가 경로로 카테고리를 가릴 수 없게 됐다 — 주소가
 * {@code /api/v1/storage/image/{fileId}} 라 번호만 보인다. 그래서 <b>공개 여부 판정이
 * 서비스로 내려왔고</b>, 여기서 그 판정을 확인한다. 함께 확인하는 것이 <b>파일 종류</b>
 * 다. 번호는 순번이라 추측할 수 있어서, {@code FileType.MUSIC}(음원 청크)을 이미지
 * API 로 달라고 하면 표지 자리로 음원 바이트가 나간다.
 *
 * <p>{@code BootstrapRunner} 가 기동 때 넣어 둔 MuBTI 캐릭터를 실제로 받아 오는 것까지
 * 본다. 그 이미지는 스토리지에 진짜로 올라가 있으므로, 이 경로가 살아 있다는 것이
 * 바이트까지 확인된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StorageImageAccessTest {

    private static final String IMAGE_URL = "/api/v1/storage/image/%s/%s";

    /** 업로드에 쓰는 최소 바이트. 내용은 중요하지 않다 — 형식 검사는 **확장자**를 본다. */
    private static final byte[] IMAGE_BYTES = "RIFF....WEBPVP8 ".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StorageService storageService;

    @Autowired
    private ObjectStorageFileRepository fileRepository;

    @Autowired
    private MubtiRepository mubtiRepository;

    @PersistenceContext
    private EntityManager em;

    /** {@code ROLE_USER}. 본인 것만 만질 수 있어야 한다. */
    private User member;

    /** 같은 {@code ROLE_USER} 지만 남이다. */
    private User stranger;

    /** {@code ROLE_SYSTEM}. 카탈로그 이미지를 다루는 배치 프로그램. */
    private User batch;

    @BeforeEach
    void setUp() {
        member = persistUser(UserRole.ROLE_USER);
        stranger = persistUser(UserRole.ROLE_USER);
        batch = persistUser(UserRole.ROLE_SYSTEM);
    }

    // ─────────────────────────────────────────────────────────────
    // 업로드
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("업로드: 앨범 표지는 ROLE_SYSTEM 이상만 쓸 수 있다")
    void catalogUploadRequiresBatchRole() throws Exception {
        // 로그인만 하면 누구나 남의 앨범 표지를 덮어쓸 수 있던 자리다.
        mockMvc.perform(upload(member, "ALBUM", "cover"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MUPID_STORAGE_WRITE_FORBIDDEN"));

        // 권한이 없다고 스토리지에 바이트를 남기고 끝나면 안 된다. 검사가 업로드보다
        // 먼저 도는 것을 여기서 본다 — 순서가 뒤집히면 이 단언이 깨진다.
        assertTrue(
            storageService.getFileByKey(StorageImageService.storageKey(FileCategory.ALBUM, "cover"))
                .isEmpty(),
            "거절된 업로드가 자리를 차지하면 안 된다");
    }

    @Test
    @DisplayName("업로드: 아티스트·곡 커버·MuBTI·스트리밍도 같은 규칙을 따른다")
    void everyOtherCategoryBlocksMemberUpload() throws Exception {
        for (FileCategory category : List.of(
            FileCategory.ARTIST, FileCategory.TRACK, FileCategory.MUBTI, FileCategory.STREAMING)) {
            String key = "probe-" + category.name();

            mockMvc.perform(upload(member, category.name(), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MUPID_STORAGE_WRITE_FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("업로드: 프로필은 본인 번호가 키여야 한다")
    void profileUploadOnlyAcceptsOwnKey() throws Exception {
        mockMvc.perform(upload(member, "PROFILE", String.valueOf(stranger.getUserId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MUPID_STORAGE_KEY_MISMATCH"));
    }

    @Test
    @DisplayName("업로드: 배치 권한이 있으면 통과하고 바이트가 실제로 올라간다")
    void batchUploadSucceeds() throws Exception {
        String key = StorageImageService.storageKey(FileCategory.ALBUM, "batch-cover");
        try {
            mockMvc.perform(upload(batch, "ALBUM", "batch-cover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.file_id").isNumber());

            assertTrue(storageService.isFileExists(key), "권한 검사를 지나면 실제로 올라간다");
        } finally {
            // 스토리지 쓰기는 트랜잭션 롤백을 따라가지 않는다. 남겨 두면 다음 실행에서
            // 앞 테스트가 올린 것을 보고 통과한다.
            storageService.deleteObjectQuietly(key);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 삭제
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("삭제: 앨범 표지는 ROLE_SYSTEM 이상만 지울 수 있다")
    void catalogDeleteRequiresBatchRole() throws Exception {
        ObjectStorageFile file = persistFile(FileCategory.ALBUM, "cover", FileType.IMG);

        mockMvc.perform(destroy(member, "ALBUM", "cover"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MUPID_STORAGE_WRITE_FORBIDDEN"));

        assertFalse(fileRepository.findById(file.getFileId()).orElseThrow().getIsDeleted(),
            "거절된 삭제는 행을 건드리지 않는다");
    }

    @Test
    @DisplayName("삭제: 아티스트·곡 커버·MuBTI·스트리밍도 같은 규칙을 따른다")
    void everyOtherCategoryBlocksMemberDelete() throws Exception {
        for (FileCategory category : List.of(
            FileCategory.ARTIST, FileCategory.TRACK, FileCategory.MUBTI, FileCategory.STREAMING)) {
            String key = "probe-" + category.name();
            persistFile(category, key, FileType.IMG);

            mockMvc.perform(destroy(member, category.name(), key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MUPID_STORAGE_WRITE_FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("삭제: 프로필은 본인 것만 지울 수 있다")
    void profileDeleteOnlyAcceptsOwnKey() throws Exception {
        persistFile(FileCategory.PROFILE, String.valueOf(stranger.getUserId()), FileType.IMG);

        mockMvc.perform(destroy(member, "PROFILE", String.valueOf(stranger.getUserId())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("MUPID_STORAGE_KEY_MISMATCH"));
    }

    @Test
    @DisplayName("삭제: 본인 프로필은 지울 수 있다")
    void profileDeleteByOwnerSucceeds() throws Exception {
        String key = String.valueOf(member.getUserId());
        ObjectStorageFile file = persistFile(FileCategory.PROFILE, key, FileType.IMG);

        // 파일을 그 사람의 프로필로 걸어 둔다. 프로필 삭제는 **파일을 지우기 전에**
        // 유저와의 연결을 끊는 콜백을 지나는데(`UserImageServiceImpl.handleDeleteImageBefore`),
        // 아무에게도 걸리지 않은 파일이면 그 콜백이 USER_NOT_FOUND 로 404 를 낸다.
        member.setImageFile(file);
        em.flush();

        // 스토리지에 실체가 없어도 된다 — 행의 소프트 삭제까지가 이 API 의 일이고,
        // 없는 오브젝트 삭제는 `StorageService` 가 조용히 넘긴다.
        mockMvc.perform(destroy(member, "PROFILE", key))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("이미지가 삭제 되었습니다."));

        assertTrue(fileRepository.findById(file.getFileId()).orElseThrow().getIsDeleted(),
            "본인 것은 지워진다");
        assertNull(member.getImageFile(), "프로필 연결도 함께 끊긴다");
    }

    @Test
    @DisplayName("삭제: 없는 파일은 404 다")
    void deleteMissingFileIsNotFound() throws Exception {
        mockMvc.perform(destroy(batch, "ALBUM", "없는표지"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MUPID_FILE_NOT_FOUND"));
    }

    // ─────────────────────────────────────────────────────────────
    // 조회 — 파일 종류
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("조회: 음원 청크를 이미지로 달라고 하면 거절한다")
    void chunkCannotBeReadAsImage() throws Exception {
        // 파일 번호는 순번이라 추측할 수 있다. 이 검사가 없으면 음원 바이트가 표지
        // 자리로 나간다 — 카테고리가 아니라 **파일 종류**로 막는 이유가 이것이다.
        ObjectStorageFile chunk = persistFile(
            FileCategory.STREAMING, "chunk_0001.rtp", FileType.MUSIC);

        mockMvc.perform(withActor(batch, get(imagePath(chunk.getFileId()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_FILE_TYPE"));
    }

    @Test
    @DisplayName("조회: 없는 번호는 404 다")
    void unknownFileIdIsNotFound() throws Exception {
        mockMvc.perform(get(imagePath(999999)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MUPID_FILE_NOT_FOUND"));
    }

    // ─────────────────────────────────────────────────────────────
    // 조회 — 공개 여부
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("조회: 카탈로그 이미지는 토큰 없이도 열린다")
    void catalogImageIsPublic() throws Exception {
        // 부트스트랩이 넣어 둔 **진짜** 캐릭터를 토큰 없이 받아 온다. `<img src>` 로 오는
        // 요청에는 Authorization 헤더를 붙일 수 없으므로 이 경로가 열려 있어야 목록의
        // 표지와 `/report` 의 캐릭터가 뜬다. 스토리지에 실체가 있는 이미지라, 200 이
        // 나온다는 것이 바이트까지 나온다는 뜻이다.
        Mubti seeded = mubtiRepository.findAll().stream()
            .filter(row -> row.getImageFile() != null)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "부트스트랩이 넣은 MuBTI 캐릭터가 없다 — 시드가 돌지 않았다"));

        mockMvc.perform(get(imagePath(seeded.getImageFile().getFileId())))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("조회: 프로필 사진은 토큰 없이 열리지 않는다")
    void profileImageIsNotPublic() throws Exception {
        ObjectStorageFile photo = persistFile(FileCategory.PROFILE, "9", FileType.IMG);

        // 경로에 카테고리가 없어 `SecurityConfig` 가 가릴 수 없다. 이 판정이 서비스로
        // 내려온 자리다.
        mockMvc.perform(get(imagePath(photo.getFileId())))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("MUPID_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("조회: 기본 프로필 이미지는 토큰 없이 열린다")
    void defaultProfileImageIsPublic() throws Exception {
        // 사진을 올린 적 없는 계정의 `image_url` 이 이 주소를 가리킨다. 닫으면 그
        // 사람들의 프로필 자리가 빈 채로 남는다 — 파일이 아니라 자리를 채우는 기본값이다.
        mockMvc.perform(get("/api/v1/storage/image/PROFILE/default"))
            .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────

    private static String imagePath(int fileId) {
        return "/api/v1/storage/image/" + fileId;
    }

    /** 요청에 CSRF 토큰과 인증 principal 을 붙인다. */
    private static RequestBuilder withActor(User actor, MockHttpServletRequestBuilder builder) {
        return builder
            .with(csrf())
            .with(authentication(tokenOf(actor)));
    }

    /**
     * 업로드 한 벌.
     *
     * <p>{@code multipart} 와 {@code delete} 는 형제 타입이라(MockMultipart 는
     * MockHttpServletRequestBuilder 의 하위가 아니다) 공통 상위인 {@link RequestBuilder}
     * 로 돌려준다.
     */
    private static RequestBuilder upload(User actor, String category, String key) {
        return multipart(IMAGE_URL.formatted(category, key))
            .file(new MockMultipartFile("imageFile", "photo.webp", "image/webp", IMAGE_BYTES))
            .with(csrf())
            .with(authentication(tokenOf(actor)));
    }

    private static RequestBuilder destroy(User actor, String category, String key) {
        return withActor(actor, delete(IMAGE_URL.formatted(category, key)));
    }

    private static UsernamePasswordAuthenticationToken tokenOf(User actor) {
        return new UsernamePasswordAuthenticationToken(actor, null, List.of());
    }

    /**
     * 메타데이터 행만 만든다. 오브젝트는 올리지 않는다 —
     * 권한·종류 검사는 스토리지에 닿기 전에 끝나므로 여기서 충분하다.
     */
    private ObjectStorageFile persistFile(FileCategory category, String key, FileType type) {
        ObjectStorageFile file = ObjectStorageFile.builder()
            .fileType(type)
            .category(category)
            .originalFileName(key)
            .storageObjectKey(StorageImageService.storageKey(category, key))
            .fileSizeBytes(IMAGE_BYTES.length)
            .mime("image/webp")
            .build();
        fileRepository.saveAndFlush(file);
        assertNotNull(file.getFileId(), "행이 저장돼야 번호로 조회할 수 있다");
        return file;
    }

    private User persistUser(UserRole role) {
        User user = User.builder()
            .email("storage-%s@mupid.test".formatted(UUID.randomUUID()))
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
