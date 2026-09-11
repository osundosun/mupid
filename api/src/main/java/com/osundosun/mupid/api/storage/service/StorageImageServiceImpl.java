package com.osundosun.mupid.api.storage.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.error.StorageImageErrorCode;
import com.osundosun.mupid.api.utils.MultipartFileUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class StorageImageServiceImpl implements StorageImageService {

    public static final long IMAGE_FILE_MAX_SIZE = 1024 * 1024 * 5; // 5 MB

    private final StorageService storageService;
    private final ApplicationContext appContext;

    @Override
    public int uploadImage(FileCategory category, String key, MultipartFile imageFile) {
        // 파일 크기 검사
        if (imageFile.getSize() > IMAGE_FILE_MAX_SIZE) {
            throw new BusinessException(StorageImageErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
        }
        // 파일 형식 검사
        if (!MultipartFileUtils.isImageExt(imageFile)) {
            throw new BusinessException(StorageImageErrorCode.INVALID_IMAGE_FILE_FORMAT);
        }

        // 파일 등록
        String storageKey = getStorageKey(category, key);
        ObjectStorageFile storageFile = storageService.uploadFile(storageKey, imageFile);

        // 카테고리 서비스 콜백
        StorageImageServiceCallback callbackService = appContext.getBean(category.getService());
        callbackService.handleUploadImageAfter(storageFile);

        // 파일 번호 반환
        return storageFile.getFileId();
    }

    @Override
    @Transactional(readOnly = true)
    public StorageFileData getImageData(FileCategory category, String key) {
        String storageKey = getStorageKey(category, key);
        Optional<ObjectStorageFile> storageFile = storageService.getFileByKey(storageKey);
        if (storageFile.isPresent()) {
            return storageService.getFileData(storageFile.get().getFileId());
        }

        // 카테고리 서비스 콜백
        StorageImageServiceCallback callbackService = appContext.getBean(category.getService());
        return callbackService.handleNoImageData(category, storageKey);
    }

    @Override
    public void deleteImage(FileCategory category, String key) {
        String storageKey = getStorageKey(category, key);
        ObjectStorageFile storageFile = storageService.getFileByKey(storageKey)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));

        // 카테고리 서비스 콜백
        StorageImageServiceCallback callbackService = appContext.getBean(category.getService());
        callbackService.handleDeleteImageBefore(storageFile);

        storageService.deleteFile(storageFile.getFileId());
    }

    @Override
    public String getStorageKey(FileCategory category, String key) {
        return "image/%s/%d".formatted(category.name().toLowerCase(), key);
    }

}
