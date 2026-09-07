package com.osundosun.mupid.api.storage.service;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileDto;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@Transactional
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final S3Client s3Client;
    private final StorageProperties props;
    private final ObjectStorageFileRepository repository;

    @Override
    public ObjectStorageFile uploadFile(String key, MultipartFile file) throws IOException {
        if (!isFileExists(key)) {
            throw new BusinessException(StorageErrorCode.DuplicatedFileKey);
        }

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(props.bucketName())
            .key(key)
            .contentType(file.getContentType())
            .contentLength(file.getSize())
            .build();
        RequestBody body = RequestBody.fromInputStream(file.getInputStream(), file.getSize());
        s3Client.putObject(request, body);

        ObjectStorageFile metaData = ObjectStorageFile.builder()
            .originalFileName(file.getOriginalFilename())
            .storageObjectKey(key)
            .fileSizeBytes(Math.toIntExact(file.getSize()))
            .build();
        return repository.save(metaData);
    }

    @Override
    @Transactional(readOnly = false)
    public StorageFileDto getFileData(int fileId) {
        ObjectStorageFile metaData = getFile(fileId);
        if (metaData == null) {
            throw new BusinessException(StorageErrorCode.FileNotFoundError);
        }

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(props.bucketName())
            .key(metaData.getStorageObjectKey())
            .build();
        ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(request);

        return new StorageFileDto(
            metaData.getOriginalFileName(),
            MediaType.parseMediaType(metaData.getContentType()),
            objectBytes.asByteArray()
        );
    }

    @Override
    public void deleteFile(int fileId) {
        ObjectStorageFile metaData = getFile(fileId);
        if (metaData == null) {
            throw new BusinessException(StorageErrorCode.FileNotFoundError);
        }
        metaData.setIsDeleted(true);

        DeleteObjectRequest request = DeleteObjectRequest.builder()
            .bucket(props.bucketName())
            .key(metaData.getStorageObjectKey())
            .build();
        s3Client.deleteObject(request);
    }

    @Override
    @Transactional(readOnly = false)
    public ObjectStorageFile getFile(int fileId) {
        ObjectStorageFile metaData = repository.findById(fileId).orElse(null);
        if (metaData == null || metaData.getIsDeleted()) {
            return null;
        }
        return metaData;
    }

    @Override
    @Transactional(readOnly = false)
    public boolean isFileExists(String key) {
        ObjectStorageFile metaData = repository.findByStorageObjectKey(key);
        if (metaData == null || metaData.getIsDeleted()) {
            return false;
        }
        return true;
    }
    
}
