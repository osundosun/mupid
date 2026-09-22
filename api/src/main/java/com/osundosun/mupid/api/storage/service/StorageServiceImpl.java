package com.osundosun.mupid.api.storage.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.dto.StorageObjectStat;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final S3Client s3Client;
    private final StorageProperties props;
    private final ObjectStorageFileRepository repository;

    @Override
    public ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file) {
        return uploadFile(fileType, category, key, file, file.getContentType());
    }

    @Override
    public ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file, String mime) {
        try {
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .contentType(mime)
            .contentLength(file.getSize())
            .build();
        RequestBody body = RequestBody.fromInputStream(file.getInputStream(), file.getSize());
        s3Client.putObject(request, body);

        String originalFilename = file.getOriginalFilename();
        int fileSize = Math.toIntExact(file.getSize());

        return getFileByKey(key)
            .map(metaData -> {
                // key 중복시 내용만 수정
                metaData.setFileType(fileType);
                metaData.setCategory(category);
                metaData.setOriginalFileName(originalFilename);
                metaData.setMime(mime);
                metaData.setFileSizeBytes(fileSize);
                return metaData;
            })
            .orElseGet(() -> repository.save(
                ObjectStorageFile.builder()
                    .fileType(fileType)
                    .category(category)
                    .originalFileName(originalFilename)
                    .storageObjectKey(key)
                    .mime(mime)
                    .fileSizeBytes(fileSize)
                    .build()
            ));
        } catch (IOException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            throw new BusinessException(StorageErrorCode.FILE_PROCESSING_FAILED);
        }
    }

    @Override
    public ObjectStorageFile uploadBytes(
        FileType fileType, FileCategory category, String key,
        String originalFileName, String mime, byte[] data
    ) {
        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .contentType(mime)
            .contentLength((long)data.length)
            .build();
        s3Client.putObject(request, RequestBody.fromBytes(data));

        return registerFile(fileType, category, key, originalFileName, mime, data.length);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StorageObjectStat> statObject(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .build();
        try {
            HeadObjectResponse response = s3Client.headObject(request);
            return Optional.of(new StorageObjectStat(response.contentLength(), response.eTag()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // SeaweedFS 는 없는 키에 404 만 주고 NoSuchKey 로 매핑되지 않을 때가 있다.
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public ObjectStorageFile registerFile(
        FileType fileType, FileCategory category, String key,
        String originalFileName, String mime, int fileSizeBytes
    ) {
        return getFileByKey(key)
            .map(metaData -> {
                // 같은 키를 다시 올린 경우 내용만 갱신한다.
                metaData.setFileType(fileType);
                metaData.setCategory(category);
                metaData.setOriginalFileName(originalFileName);
                metaData.setMime(mime);
                metaData.setFileSizeBytes(fileSizeBytes);
                return metaData;
            })
            .orElseGet(() -> repository.save(
                ObjectStorageFile.builder()
                    .fileType(fileType)
                    .category(category)
                    .originalFileName(originalFileName)
                    .storageObjectKey(key)
                    .mime(mime)
                    .fileSizeBytes(fileSizeBytes)
                    .build()
            ));
    }

    @Override
    @Transactional(readOnly = false)
    public StorageFileData getFileData(int fileId) {
        ObjectStorageFile metaData = getFile(fileId)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(props.bucketName())
            .key(metaData.getStorageObjectKey())
            .build();
        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);

        return new StorageFileData(
            metaData.getOriginalFileName(),
            MediaType.parseMediaType(metaData.getMime()),
            objectBytes.asByteArray()
        );
    }

    @Override
    public void deleteFile(int fileId) {
        ObjectStorageFile metaData = getFile(fileId)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));
        metaData.setIsDeleted(true);

        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(props.bucketName())
            .key(metaData.getStorageObjectKey())
            .build();
        s3Client.deleteObject(request);
    }

    @Override
    @Transactional(readOnly = false)
    public Optional<ObjectStorageFile> getFile(int fileId) {
        ObjectStorageFile metaData = repository.findById(fileId).orElse(null);
        if (metaData == null || metaData.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(metaData);
    }

    @Override
    public Optional<ObjectStorageFile> getFileByKey(String key) {
        ObjectStorageFile metaData = repository
            .findByStorageObjectKeyAndIsDeletedFalse(key).orElse(null);
        if (metaData == null || metaData.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(metaData);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> getFileDataByKey(String key) {
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .build();
        try {
            return Optional.of(s3Client.getObjectAsBytes(request).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            // SeaweedFS 는 없는 키에 404 만 주고 NoSuchKey 로 매핑되지 않을 때가 있다
            // (statObject 와 같은 사정).
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = false)
    public int renameStorageObjectKey(String stale, String wanted) {
        return repository.renameStorageObjectKey(stale, wanted);
    }

    @Override
    @Transactional(readOnly = false)
    public boolean isFileExists(String key) {
        ObjectStorageFile metaData = repository
            .findByStorageObjectKeyAndIsDeletedFalse(key).orElse(null);
        if (metaData == null || metaData.getIsDeleted()) {
            return false;
        }
        return true;
    }

    @Override
    @Transactional(readOnly = false)
    public void deleteObjectQuietly(String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .build();
        try {
            s3Client.deleteObject(request);
            // 이 키로 메타데이터 행이 남아 있으면 함께 정리한다. 위에서 말한 대로 그 행이
            // 있다는 보장이 없다 — SeaweedFS 처럼 객체와 메타데이터가 한 경로인 스토리지에서는
            // 객체를 지우는 순간 행도 같이 사라진다.
            repository.findByStorageObjectKeyAndIsDeletedFalse(key)
                .ifPresent(metaData -> metaData.setIsDeleted(true));
        } catch (RuntimeException e) {
            // 옛 키 정리는 뒷정이다. 실패해도 본래 하려던 일(새 키로 올리기)은 이미 끝났다.
            log.warn("쓰이지 않는 오브젝트를 지우지 못했습니다. key={}", key, e);
        }
    }

}
