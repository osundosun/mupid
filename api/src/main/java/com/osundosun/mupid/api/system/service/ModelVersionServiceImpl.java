package com.osundosun.mupid.api.system.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.system.entity.ModelVersion;
import com.osundosun.mupid.api.system.entity.SystemObject;
import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;
import com.osundosun.mupid.api.system.error.SystemObjectErrorCode;
import com.osundosun.mupid.api.system.repository.ModelVersionRepository;
import com.osundosun.mupid.api.system.repository.SystemObjectRepository;
import com.osundosun.mupid.api.system.response.ModelGetResponse;
import com.osundosun.mupid.api.system.response.ModelVersionStatusResponse;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ModelVersionServiceImpl implements ModelVersionService {

    private final ModelVersionRepository versionRepository;
    private final SystemObjectRepository objectRepository;

    @Override
    @Transactional(readOnly = true)
    public ModelGetResponse getModel(String name) {
        List<ModelVersion> versions = versionRepository.findByNameOrderByModelVersionIdDesc(name);
        if (versions.isEmpty()) {
            throw new BusinessException(SystemObjectErrorCode.MODEL_NOT_FOUND);
        }
        Map<Integer, List<SystemObject>> filesByVersion = objectRepository
            .findByModelVersion_ModelVersionIdIn(
                versions.stream().map(ModelVersion::getModelVersionId).toList())
            .stream()
            .collect(Collectors.groupingBy(it -> it.getModelVersion().getModelVersionId()));

        return new ModelGetResponse(
            name,
            current(versions).map(ModelVersion::getVersion).orElse(null),
            versions.stream()
                .map(it -> new ModelGetResponse.VersionItem(
                    it.getVersion(),
                    it.getStatus(),
                    it.getManifestSha256(),
                    it.declaredFileNames().size(),
                    filesByVersion.getOrDefault(it.getModelVersionId(), List.of()).stream()
                        .sorted(Comparator.comparing(SystemObject::getFileName))
                        .map(file -> new ModelGetResponse.FileItem(
                            file.getObjectId(),
                            file.getFileName(),
                            file.getSizeBytes(),
                            file.getSha256(),
                            file.getStatus()))
                        .toList(),
                    it.getCreatedAt(),
                    it.getCandidateAt(),
                    it.getAdoptedAt(),
                    it.getRetiredAt()))
                .toList());
    }

    @Override
    public ModelVersionStatusResponse adopt(String name, String version) {
        ModelVersion target = getVersion(name, version);
        switch (target.getStatus()) {
            case UPLOADING -> throw new BusinessException(SystemObjectErrorCode.MODEL_VERSION_NOT_READY);
            // 폐기는 Qdrant 의 그 버전 벡터를 지운 뒤에 한다(§6.3 표). 다시 채택하면 서버가
            // 기동 검사(§10.2-1)에서 멈춘다 — 여기서 먼저 막는다.
            case RETIRED -> throw new BusinessException(SystemObjectErrorCode.MODEL_VERSION_RETIRED);
            case CANDIDATE, ADOPTED -> {
                target.setStatus(ModelVersionStatus.ADOPTED);
                target.setAdoptedAt(LocalDateTime.now());
            }
        }
        return toResponse(target);
    }

    @Override
    public ModelVersionStatusResponse retire(String name, String version) {
        ModelVersion target = getVersion(name, version);
        if (target.getStatus() == ModelVersionStatus.RETIRED) {
            return toResponse(target);
        }
        // 지금 쓰는 버전을 폐기하면 "지금 쓰는 버전" 이 조용히 이전 채택 버전으로 넘어간다.
        // 운영 서버는 그대로인데 조회만 바뀌는 것이라, 다음 버전을 먼저 채택하게 한다.
        boolean inService = current(versionRepository.findByNameOrderByModelVersionIdDesc(name))
            .map(it -> it.getModelVersionId().equals(target.getModelVersionId()))
            .orElse(false);
        if (inService) {
            throw new BusinessException(SystemObjectErrorCode.MODEL_VERSION_IN_SERVICE);
        }
        target.setStatus(ModelVersionStatus.RETIRED);
        target.setRetiredAt(LocalDateTime.now());
        return toResponse(target);
    }

    private ModelVersion getVersion(String name, String version) {
        return versionRepository.findByNameAndVersion(name, version)
            .orElseThrow(() -> new BusinessException(SystemObjectErrorCode.MODEL_VERSION_NOT_FOUND));
    }

    /** 지금 쓰는 버전 — {@code ADOPTED} 중 가장 늦게 채택된 것 */
    private static Optional<ModelVersion> current(List<ModelVersion> versions) {
        return versions.stream()
            .filter(it -> it.getStatus() == ModelVersionStatus.ADOPTED)
            .max(Comparator.comparing(ModelVersion::getAdoptedAt)
                .thenComparing(ModelVersion::getModelVersionId));
    }

    private ModelVersionStatusResponse toResponse(ModelVersion target) {
        String currentVersion = current(versionRepository.findByNameOrderByModelVersionIdDesc(target.getName()))
            .map(ModelVersion::getVersion)
            .orElse(null);
        return new ModelVersionStatusResponse(
            target.getName(),
            target.getVersion(),
            target.getStatus(),
            currentVersion,
            target.getAdoptedAt(),
            target.getRetiredAt());
    }

}
