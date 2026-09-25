package com.osundosun.mupid.api.system.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageObjectDigest;
import com.osundosun.mupid.api.storage.dto.StorageObjectStat;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.system.dto.SystemObjectRedirect;
import com.osundosun.mupid.api.system.entity.ModelVersion;
import com.osundosun.mupid.api.system.entity.SystemObject;
import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;
import com.osundosun.mupid.api.system.enumerate.SystemObjectKind;
import com.osundosun.mupid.api.system.enumerate.SystemObjectStatus;
import com.osundosun.mupid.api.system.error.SystemObjectErrorCode;
import com.osundosun.mupid.api.system.repository.ModelVersionRepository;
import com.osundosun.mupid.api.system.repository.SystemObjectRepository;
import com.osundosun.mupid.api.system.request.SystemObjectCreateRequest;
import com.osundosun.mupid.api.system.response.SystemObjectResponse;

@Service
public class SystemObjectServiceImpl implements SystemObjectService {

    /**
     * 파일 하나의 상한. 모델 묶음의 가장 큰 파일(온보딩 씨앗·인코더)도 수십 MB 라 넉넉하다.
     *
     * <p>청크와 같이 이 검사는 Content-Length 로만 한다(① 신고 크기·② 업로드 길이). Nginx 의
     * {@code client_max_body_size} 는 이보다 크게 두어 최후의 방어선으로만 쓴다.
     * {@code object_storage_files.file_size_bytes} 가 INT 라 2GB 를 넘길 수도 없다.
     */
    public static final long OBJECT_FILE_MAX_SIZE = 512L * 1024 * 1024; // 512 MB

    /** 버전마다 반드시 있어야 하는 파일. 체크섬·지표의 정본이다(§6.3) */
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    /** 모델 이름 — 저장 키의 한 마디가 된다. 소문자·숫자·`_`·`-` */
    private static final Pattern MODEL_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");

    /** 버전 — 설계가 정한 모양 `v{N}` 만 받는다 */
    private static final Pattern MODEL_VERSION = Pattern.compile("^v[0-9]{1,6}$");

    /**
     * 파일명 — 경로 구분자가 들어갈 수 없다. `..` 도 마디 하나로는 막히지만(`/` 가 없다)
     * 첫 글자를 영숫자로 두어 숨김 파일·상대 경로처럼 보이는 이름을 아예 받지 않는다.
     */
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    private final SystemObjectRepository objectRepository;
    private final ModelVersionRepository versionRepository;
    private final StorageService storageService;
    private final StorageProperties storageProps;
    private final TransactionTemplate transaction;

    public SystemObjectServiceImpl(
        SystemObjectRepository objectRepository,
        ModelVersionRepository versionRepository,
        StorageService storageService,
        StorageProperties storageProps,
        PlatformTransactionManager transactionManager
    ) {
        this.objectRepository = objectRepository;
        this.versionRepository = versionRepository;
        this.storageService = storageService;
        this.storageProps = storageProps;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ─────────────────────────────────────────────────────────────
    // ① 요청
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SystemObjectResponse request(SystemObjectCreateRequest request) {
        SystemObjectKind kind = SystemObjectKind.find(request.kind())
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.UNSUPPORTED_OBJECT_KIND));
        String name = request.name().trim();
        String version = request.version().trim();
        String fileName = request.fileName().trim();
        String sha256 = request.sha256().trim().toLowerCase(Locale.ROOT);

        requireModelCoordinates(name, version);
        requireFileName(kind, fileName);
        if (!SHA256.matcher(sha256).matches()) {
            throw new BusinessException(SystemObjectErrorCode.INVALID_SHA256);
        }
        if (request.sizeBytes() > OBJECT_FILE_MAX_SIZE) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_FILE_SIZE_EXCEEDED);
        }

        List<String> versionFiles = request.versionFiles().stream().map(String::trim).toList();
        versionFiles.forEach(it -> requireFileName(kind, it));
        if (!versionFiles.contains(MANIFEST_FILE_NAME)) {
            throw new BusinessException(SystemObjectErrorCode.MANIFEST_REQUIRED);
        }
        if (!versionFiles.contains(fileName)) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_FILE_NOT_DECLARED);
        }

        // 첫 요청이 버전의 파일 목록을 정한다. 뒤에 다른 목록이 오면 같은 버전 번호로
        // 다른 묶음을 만들려는 것이다.
        String declared = ModelVersion.joinFileNames(versionFiles);
        ModelVersion modelVersion = versionRepository.findByNameAndVersion(name, version)
            .orElseGet(() -> versionRepository.save(ModelVersion.builder()
                .name(name)
                .version(version)
                .status(ModelVersionStatus.UPLOADING)
                .fileNames(declared)
                .build()));
        if (!modelVersion.getFileNames().equals(declared)) {
            throw new BusinessException(SystemObjectErrorCode.MODEL_VERSION_FILES_MISMATCH);
        }

        String storageKey = storageKey(kind, name, version, fileName);
        SystemObject existing = objectRepository.findByStorageObjectKey(storageKey).orElse(null);
        if (existing != null) {
            // 불변: 같은 자리에 다른 내용은 받지 않는다. 같은 내용이면 멱등 — 배치가 중간에
            // 죽어도 처음부터 다시 돌릴 수 있어야 한다.
            if (!existing.getSha256().equals(sha256) || !existing.getSizeBytes().equals(request.sizeBytes())) {
                throw new BusinessException(SystemObjectErrorCode.OBJECT_IMMUTABLE);
            }
            return toResponse(existing);
        }

        SystemObject created = objectRepository.save(SystemObject.builder()
            .kind(kind)
            .modelVersion(modelVersion)
            .fileName(fileName)
            .storageObjectKey(storageKey)
            .sizeBytes(request.sizeBytes())
            .sha256(sha256)
            .status(SystemObjectStatus.UPLOADING)
            .build());
        return toResponse(created);
    }

    // ─────────────────────────────────────────────────────────────
    // ② 업로드 허가 (Nginx auth_request)
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public String authorizeUpload(int objectId, Long contentLength) {
        SystemObject object = getObject(objectId);

        // 완료는 "서버가 sha256 을 확인했다" 이다. 그 뒤에 덮어쓰면 확인이 거짓이 된다 —
        // Filer 는 같은 경로 PUT 을 그냥 덮어쓰므로 여기서 막아야 한다.
        if (object.getStatus() == SystemObjectStatus.COMPLETED) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_ALREADY_COMPLETED);
        }
        // 청크와 달리 길이 없는 업로드를 받지 않는다. 신고한 크기와 맞춰 볼 수 없고,
        // 그러면 Nginx 상한까지 아무 바이트나 흘러들어 올 수 있다.
        if (contentLength == null) {
            throw new BusinessException(SystemObjectErrorCode.UPLOAD_LENGTH_REQUIRED);
        }
        if (contentLength > OBJECT_FILE_MAX_SIZE) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_FILE_SIZE_EXCEEDED);
        }
        if (contentLength.longValue() != object.getSizeBytes()) {
            throw new BusinessException(SystemObjectErrorCode.UPLOAD_SIZE_MISMATCH);
        }
        return filerPath(object.getStorageObjectKey());
    }

    // ─────────────────────────────────────────────────────────────
    // ③ 완료
    // ─────────────────────────────────────────────────────────────

    /**
     * 트랜잭션을 둘로 나눈다. 가운데의 sha256 계산은 수백 MB 를 읽을 수 있는데, 그동안
     * DB 연결과 버전 행 잠금을 쥐고 있을 이유가 없다.
     */
    @Override
    public SystemObjectResponse complete(int objectId) {
        Snapshot snapshot = transaction.execute(status -> {
            SystemObject object = getObject(objectId);
            return new Snapshot(
                object.getStatus(),
                object.getStorageObjectKey(),
                object.getSizeBytes(),
                object.getSha256(),
                object.getModelVersion().getModelVersionId());
        });
        if (snapshot.status() == SystemObjectStatus.COMPLETED) {
            return transaction.execute(status -> toResponse(getObject(objectId)));
        }
        String storageKey = snapshot.storageKey();
        long declaredSize = snapshot.sizeBytes();

        // 크기는 HEAD 로 먼저 본다. 다르면 통째로 읽을 필요도 없다.
        StorageObjectStat stat = storageService.statObject(storageKey)
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.OBJECT_NOT_UPLOADED));
        if (stat.sizeBytes() != declaredSize) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_SIZE_MISMATCH);
        }
        StorageObjectDigest digest = storageService.digestObject(storageKey)
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.OBJECT_NOT_UPLOADED));
        if (digest.sizeBytes() != declaredSize) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_SIZE_MISMATCH);
        }
        // 틀려도 오브젝트는 UPLOADING 으로 남는다. 올리는 쪽이 ② 부터 다시 하면 된다.
        if (!digest.sha256().equals(snapshot.sha256())) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_SHA256_MISMATCH);
        }

        return transaction.execute(status -> {
            // 같은 버전의 파일 완료는 버전 행에서 줄을 선다. 잠금을 먼저 잡아야 뒤의
            // 읽기가 앞서 커밋된 완료를 본다 (MySQL REPEATABLE READ 의 스냅숏은 첫 읽기 때 생긴다).
            ModelVersion modelVersion = versionRepository.findByIdForUpdate(snapshot.modelVersionId())
                .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.MODEL_VERSION_NOT_FOUND));
            SystemObject object = getObject(objectId);
            if (object.getStatus() == SystemObjectStatus.COMPLETED) {
                return toResponse(object);
            }

            ObjectStorageFile file = storageService.registerFile(
                FileType.MODEL,
                FileCategory.MODEL,
                storageKey,
                object.getFileName(),
                mimeOf(object.getFileName()),
                Math.toIntExact(declaredSize));
            object.setFile(file);
            object.setStatus(SystemObjectStatus.COMPLETED);
            object.setCompletedAt(LocalDateTime.now());

            promoteIfComplete(modelVersion);
            return toResponse(object);
        });
    }

    /**
     * 선언한 파일이 모두 완료됐으면 버전을 후보로 올린다. 후보·채택·폐기는 건드리지 않는다.
     */
    private void promoteIfComplete(ModelVersion modelVersion) {
        if (modelVersion.getStatus() != ModelVersionStatus.UPLOADING) {
            return;
        }
        List<SystemObject> objects = objectRepository
            .findByModelVersion_ModelVersionId(modelVersion.getModelVersionId());
        Set<String> completed = objects.stream()
            .filter(it -> it.getStatus() == SystemObjectStatus.COMPLETED)
            .map(SystemObject::getFileName)
            .collect(Collectors.toSet());
        if (!completed.containsAll(modelVersion.declaredFileNames())) {
            return;
        }
        String manifestSha256 = objects.stream()
            .filter(it -> it.getFileName().equals(MANIFEST_FILE_NAME))
            .map(SystemObject::getSha256)
            .findFirst()
            .orElseThrow();
        modelVersion.setStatus(ModelVersionStatus.CANDIDATE);
        modelVersion.setManifestSha256(manifestSha256);
        modelVersion.setCandidateAt(LocalDateTime.now());
    }

    // ─────────────────────────────────────────────────────────────
    // 받기
    // ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public SystemObjectRedirect getRedirect(int objectId) {
        return toRedirect(getObject(objectId));
    }

    @Override
    @Transactional(readOnly = true)
    public SystemObjectRedirect getRedirect(String name, String version, String fileName) {
        requireModelCoordinates(name, version);
        requireFileName(SystemObjectKind.MODEL, fileName);
        SystemObject object = objectRepository
            .findByStorageObjectKey(storageKey(SystemObjectKind.MODEL, name, version, fileName))
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.SYSTEM_OBJECT_NOT_FOUND));
        return toRedirect(object);
    }

    /**
     * 완료된 것만 내보낸다. 폐기된 버전의 파일도 받을 수 있다 — 되돌리기를 위해 남긴 것이다.
     */
    private SystemObjectRedirect toRedirect(SystemObject object) {
        if (object.getStatus() != SystemObjectStatus.COMPLETED) {
            throw new BusinessException(SystemObjectErrorCode.OBJECT_NOT_COMPLETED);
        }
        return new SystemObjectRedirect(
            "/storage" + filerPath(object.getStorageObjectKey()),
            mimeOf(object.getFileName()),
            object.getFileName());
    }

    // ─────────────────────────────────────────────────────────────

    /** 완료 단계가 트랜잭션 밖으로 들고 나가는 값. 엔티티를 들고 나가면 지연 로딩이 깨진다 */
    private record Snapshot(
        SystemObjectStatus status, String storageKey, long sizeBytes, String sha256, int modelVersionId) {
    }

    private SystemObject getObject(int objectId) {
        return objectRepository.findById(objectId)
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.SYSTEM_OBJECT_NOT_FOUND));
    }

    private static void requireModelCoordinates(String name, String version) {
        if (name == null || !MODEL_NAME.matcher(name).matches()) {
            throw new BusinessException(SystemObjectErrorCode.INVALID_MODEL_NAME);
        }
        if (version == null || !MODEL_VERSION.matcher(version).matches()) {
            throw new BusinessException(SystemObjectErrorCode.INVALID_MODEL_VERSION);
        }
    }

    private static void requireFileName(SystemObjectKind kind, String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new BusinessException(SystemObjectErrorCode.INVALID_OBJECT_FILE_NAME);
        }
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!kind.getAllowedExtensions().contains(extension)) {
            throw new BusinessException(SystemObjectErrorCode.INVALID_OBJECT_FILE_NAME);
        }
    }

    /**
     * 저장 키. 예) system/model/stage1/v3/user_encoder.npz
     */
    static String storageKey(SystemObjectKind kind, String name, String version, String fileName) {
        return "system/%s/%s/%s/%s".formatted(kind.getPrefix(), name, version, fileName);
    }

    /**
     * Filer 기준 경로. Nginx 가 업로드 본문을 이 경로로 흘려보내고, 스프링은 같은 오브젝트를
     * S3 게이트웨이로 읽는다 — 같은 버킷이다({@code ChunkServiceImpl} 과 같은 규칙).
     */
    private String filerPath(String key) {
        return "/buckets/%s/%s".formatted(storageProps.bucketName(), key);
    }

    private static String mimeOf(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".json")
            ? "application/json"
            : "application/octet-stream";
    }

    private static SystemObjectResponse toResponse(SystemObject object) {
        ModelVersion modelVersion = object.getModelVersion();
        return new SystemObjectResponse(
            object.getObjectId(),
            object.getKind().name(),
            modelVersion.getName(),
            modelVersion.getVersion(),
            object.getFileName(),
            object.getSizeBytes(),
            object.getSha256(),
            object.getStatus(),
            object.getStatus() == SystemObjectStatus.UPLOADING
                ? "/upload/system/" + object.getObjectId()
                : null,
            modelVersion.getStatus());
    }

}
