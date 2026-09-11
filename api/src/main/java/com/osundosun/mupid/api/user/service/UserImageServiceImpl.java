package com.osundosun.mupid.api.user.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.service.StorageImageServiceCallback;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.utils.SecurityUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserImageServiceImpl implements StorageImageServiceCallback {

    public static final long USER_IMAGE_FILE_MAX_SIZE = 1024 * 1024 * 5; // 5 MB

    private final UserService userService;
    private final StorageService storageService;

    @Override
    public void handleUploadImageAfter(ObjectStorageFile file) {
        User user = SecurityUtils.getAuthUser();
        user.setImageFile(file);
    }

    @Override
    public void handleDeleteImageBefore(ObjectStorageFile file) {
        User user = userService.getUserByImageFileId(file.getFileId())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        ObjectStorageFile storageFile = user.getImageFile();

        if (storageFile == null) {
            return;
        }

        user.setImageFile(null);
        storageService.deleteFile(storageFile.getFileId());
    }

    @Override
    @Transactional(readOnly = true)
    public StorageFileData handleNoImageData(FileCategory category, String key) {
        ClassPathResource resource = new ClassPathResource("img/default-user-profile.png");
        try (InputStream inputStream = resource.getInputStream()) {
            return new StorageFileData(
                "default-user-profile.png",
                MediaType.IMAGE_PNG,
                StreamUtils.copyToByteArray(inputStream)
            );
        } catch (IOException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            throw new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
    
}
