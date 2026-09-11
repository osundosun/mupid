package com.osundosun.mupid.api.user.service;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.StorageFileCategory;
import com.osundosun.mupid.api.storage.enumerate.StorageFileType;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.error.UserImageErrorCode;
import com.osundosun.mupid.api.utils.MultipartFileUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserImageServiceImpl implements UserImageService {

    public static final long USER_IMAGE_FILE_MAX_SIZE = 1024 * 1024 * 5; // 5 MB

    private final UserService userService;
    private final StorageService storageService;

    @Override
    public void updateUserImage(int userId, MultipartFile imageFile) throws IOException {
        if (imageFile.getSize() > USER_IMAGE_FILE_MAX_SIZE) {
            // 파일 크기 검사
            throw new BusinessException(UserImageErrorCode.USER_IMAGE_FILE_SIZE_EXCEEDED);
        }
        if (!MultipartFileUtils.isImageExt(imageFile)) {
            // 파일 형식 검사
            throw new BusinessException(UserImageErrorCode.INVALID_USER_IMAGE_FILE_FORMAT);
        }

        // 파일 등록
        String key = "user/image/%d".formatted(userId);
        ObjectStorageFile storageFile = storageService.uploadFile(
            key,
            imageFile,
            StorageFileType.IMG,
            StorageFileCategory.PROFILE
        );

        // 유저 데이터 수정
        User user = userService
            .getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        user.setImageFile(storageFile);
    }

    @Override
    public void deleteUserImage(int userId) {
        User user = userService
            .getUser(userId)
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
    public StorageFileData getUserImageData(int userId) {
        User user = userService
            .getUser(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        ObjectStorageFile storageFile = user.getImageFile();
        if (storageFile != null) {
            return storageService.getFileData(storageFile.getFileId());
        }

        // 만약 사용자 프로필 이미지가 없으면 기본 이미지를 반환한다.
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
