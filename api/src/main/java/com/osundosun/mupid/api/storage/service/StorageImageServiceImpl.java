package com.osundosun.mupid.api.storage.service;

import java.util.Optional;

import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.auth.error.AuthErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.error.StorageImageErrorCode;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.service.UserImageServiceImpl;
import com.osundosun.mupid.api.utils.MultipartFileUtils;
import com.osundosun.mupid.api.utils.SecurityUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class StorageImageServiceImpl implements StorageImageService {

    public static final long IMAGE_FILE_MAX_SIZE = 1024 * 1024 * 5; // 5 MB

    private final StorageService storageService;
    private final ApplicationContext appContext;

    @Override
    public int uploadImage(FileCategory category, String key, MultipartFile imageFile, User actor) {
        // 파일 크기 검사
        if (imageFile.getSize() > IMAGE_FILE_MAX_SIZE) {
            throw new BusinessException(StorageImageErrorCode.IMAGE_FILE_SIZE_EXCEEDED);
        }
        // 파일 형식 검사
        if (!MultipartFileUtils.isImageExt(imageFile)) {
            throw new BusinessException(StorageImageErrorCode.INVALID_IMAGE_FILE_FORMAT);
        }

        String storageKey = getStorageKey(category, key);

        /*
         * 이 자리에 쓸 수 있는 사람인가.
         *
         * 검사하는 자리가 업로드와 삭제 둘인데 규칙은 카테고리가 들고 있는 정책 하나다
         * (`FileCategory.accessPolicy`). 한쪽에만 검사를 두면 대칭이 깨진다 — 실제로
         * PROFILE 에만 소유자 검사가 있어서 나머지 카테고리는 로그인만 하면 누구나
         * 남의 표지를 덮어쓸 수 있었다.
         *
         * 업로드를 먼저 검사하는 것은 뒤에서 오브젝트를 건드리기 때문이다. 검사가
         * 뒤로 가면 권한 없는 요청이 스토리지에 바이트를 남기고 끝난다.
         */
        category.getAccessPolicy().check(actor, storageKey);

        // 파일 등록
        ObjectStorageFile storageFile = storageService.uploadFile(FileType.IMG, category, storageKey, imageFile);

        // 카테고리 서비스 콜백 (등록된 카테고리에만 있다)
        StorageImageServiceCallback callback = getCallbackService(category);
        if (callback != null) {
            callback.handleUploadImageAfter(storageFile);
        }

        // 파일 번호 반환
        return storageFile.getFileId();
    }

    @Override
    @Transactional(readOnly = true)
    public StorageFileData getImageData(int fileId) {
        ObjectStorageFile storageFile = storageService.getFile(fileId)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));

        /*
         * 이미지 API 로 다른 종류의 파일을 달라고 하면 안 된다.
         *
         * 파일 번호는 순번이라 추측할 수 있다. 이 검사가 없으면 음원 청크
         * (`FileType.MUSIC`) 바이트가 표지 자리로 그대로 나간다. `STREAMING` 카테고리도
         * 같은 테이블을 쓰므로 카테고리가 아니라 **파일 종류**로 막는다.
         */
        if (storageFile.getFileType() != FileType.IMG) {
            throw new BusinessException(StorageImageErrorCode.INVALID_FILE_TYPE);
        }

        /*
         * 읽기가 공개가 아닌 카테고리(PROFILE·STREAMING)는 토큰이 있어야 한다.
         *
         * 예전에는 경로에 카테고리가 있어서 `SecurityConfig` 가 경로로 가릴 수 있었다.
         * 번호로 읽는 지금은 경로만 보고 카테고리를 알 수 없으므로 여기서 본다 —
         * 판단 기준은 카테고리의 정책 하나이고, 시큐리티의 경로 규칙은 그 결과를
         * 경로로 적어 둔 것뿐이다.
         *
         * 403 이 아니라 401 인 것은 이 자리가 "너에게 권한이 없다"가 아니라 "너가
         * 누구인지 모르겠다"이기 때문이다. 프런트는 401 을 보고 로그인으로 보낸다.
         */
        if (!storageFile.getCategory().getAccessPolicy().isPubliclyReadable()
            && SecurityUtils.getAuthUser() == null) {
            throw new BusinessException(AuthErrorCode.UNAUTHORIZED);
        }

        return storageService.getFileData(fileId);
    }

    /**
     * 기본 프로필 이미지.
     *
     * 파일이 아니라 classpath 자산이라 파일 번호가 없다. 그래서 번호 기반 조회로
     * 옮기지 못했고, 이 자리만 별도 경로로 남았다. 실체는 프로필 카테고리의 콜백이
     * 들고 있다 — 기본 이미지가 무엇인지는 그쪽이 안다.
     */
    @Override
    @Transactional(readOnly = true)
    public StorageFileData getDefaultProfileImage() {
        StorageImageServiceCallback callback = getCallbackService(FileCategory.PROFILE);
        if (callback == null) {
            throw new BusinessException(StorageErrorCode.FILE_NOT_FOUND);
        }
        return callback.handleNoImageData(FileCategory.PROFILE, UserImageServiceImpl.DEFAULT_IMAGE_KEY);
    }

    @Override
    public void deleteImage(FileCategory category, String key, User actor) {
        String storageKey = getStorageKey(category, key);
        ObjectStorageFile storageFile = storageService.getFileByKey(storageKey)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));

        // 이미지가 아닌 행은 이 API 로 지울 수 없다. `deleteFile` 은 스토리지 오브젝트까지
        // 지우므로, 이 검사가 없으면 음원 청크가 이미지 삭제 요청 한 번에 사라진다.
        if (storageFile.getFileType() != FileType.IMG) {
            throw new BusinessException(StorageImageErrorCode.INVALID_FILE_TYPE);
        }

        // 업로드와 같은 정책을 본다. 한쪽만 잠그면 잠그지 않은 쪽으로 우회된다.
        category.getAccessPolicy().check(actor, storageKey);

        // 카테고리 서비스 콜백 (등록된 카테고리에만 있다)
        StorageImageServiceCallback callback = getCallbackService(category);
        if (callback != null) {
            callback.handleDeleteImageBefore(storageFile);
        }

        storageService.deleteFile(storageFile.getFileId());
    }

    @Override
    public String getStorageKey(FileCategory category, String key) {
        // 모양을 아는 곳은 `StorageImageService.storageKey` 하나다 — 조회 URL 이 그 모양을
        // 되짚어 만들어지므로, 여기서 손으로 적으면 둘이 조용히 갈라진다.
        return StorageImageService.storageKey(category, key);
    }

    /**
     * 카테고리에 콜백 서비스가 없으면 null 을 반환한다.
     * ARTIST·ALBUM·TRACK·MUBTI 처럼 업로드 후 따로 할 일이 없는 카테고리가 있다.
     */
    private StorageImageServiceCallback getCallbackService(FileCategory category) {
        if (category.getService() == null) {
            return null;
        }
        return appContext.getBean(category.getService());
    }

}
