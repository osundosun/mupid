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
import com.osundosun.mupid.api.storage.service.StorageImageService;
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
public class UserImageServiceImpl implements UserImageService, StorageImageServiceCallback {

    /**
     * 사진을 올린 적 없는 계정에 내려 주는 기본 이미지.
     *
     * <p>PNG 가 아니라 WebP 다 — 같은 그림이 75KB 에서 9KB 로 줄어 jar 와 응답이 함께
     * 가벼워진다. 파일 이름이 응답의 `Content-Disposition` 에 그대로 나가므로
     * 확장자를 바꾸면 `api/docs/Storage`, `api/docs/User` 의 예시도 같이 고쳐야 한다.
     */
    private static final String DEFAULT_PROFILE_IMAGE = "default-user-profile.webp";

    /** {@link MediaType} 에 WebP 상수가 없어 문자열로 둔다. */
    private static final String DEFAULT_PROFILE_IMAGE_MIME = "image/webp";

    public static final long USER_IMAGE_FILE_MAX_SIZE = 1024 * 1024 * 5; // 5 MB

    /** 사진을 올린 적이 없는 사람이 가리키는 키. 이 키에는 파일이 없다. */
    public static final String DEFAULT_IMAGE_KEY = "default";

    private final UserService userService;
    private final StorageService storageService;

    /**
     * 프로필 사진 조회 URL.
     *
     * 사진을 올린 적이 없으면 기본 이미지 주소를 가리킨다. 그 경로에는 파일이 없고
     * `handleNoImageData` 가 기본 이미지를 돌려주므로, **항상 받아올 것이 있는
     * URL 이다.** 화면은 null 검사 없이 그대로 쓰면 된다.
     *
     * 다만 `<img src>` 에 바로 걸 수는 없다 — PROFILE 은 공개 경로가 아니라
     * (`SecurityConfig`) 토큰이 있어야 하고 `<img>` 는 헤더를 달지 못한다.
     * 화면은 받아 온 바이트를 object URL 로 바꿔 쓴다
     * (`frontend/lib/profile-photo.ts`).
     */
    @Override
    @Transactional(readOnly = true)
    public String getProfileImageUrl(User user) {
        // 인증 principal 의 `imageFile` 은 이미 닫힌 세션에 묶인 프록시라 여기서
        // 건드리면 LazyInitializationException 이다. 지금 세션에서 다시 읽는다.
        //
        // `imageUrl(file)` 은 파일 번호로 주소를 만든다. 파일이 없거나 지워졌거나
        // 이미지가 아니면 null 이고, 그때만 기본 이미지로 내려간다.
        String url = userService.getUser(user.getUserId())
            .map(User::getImageFile)
            .map(StorageImageService::imageUrl)
            .orElse(null);
        return url != null ? url : StorageImageService.defaultProfileImageUrl();
    }

    /**
     * 업로드한 파일을 그 사람의 프로필 사진으로 건다.
     *
     * 인증 principal 은 액세스 토큰으로 만든 것이라 영속 상태가 아니다. 여기에
     * 바로 `setImageFile` 을 하면 **아무 데도 저장되지 않는다** — 파일만 올라가고
     * 프로필은 기본 이미지로 남는다. 반드시 DB 에서 다시 읽어 고친다.
     *
     * 저장 키가 본인 것인지 확인하는 일은 여기서 하지 않는다. 그 검사는 업로드
     * 입구(`StorageImageServiceImpl.uploadImage`)가 카테고리 정책으로 이미 했다 —
     * 두 곳에서 하면 규칙이 둘로 갈라진다 (`ImageAccessPolicy.OWNER`).
     */
    @Override
    public void handleUploadImageAfter(ObjectStorageFile file) {
        User principal = SecurityUtils.getAuthUser();
        if (principal == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        User user = userService.getUser(principal.getUserId())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        ObjectStorageFile previous = user.getImageFile();
        user.setImageFile(file);

        // 사진을 바꾸면 이전 파일은 쓸 데가 없다. 남겨 두면 스토리지만 찬다.
        if (previous != null && !previous.getFileId().equals(file.getFileId())) {
            storageService.deleteFile(previous.getFileId());
        }
    }

    /**
     * 사진을 지우기 전에 사용자와의 연결을 끊는다.
     *
     * **파일 자체는 지우지 않는다.** 호출부(`StorageImageService.deleteImage`)가
     * 콜백을 부른 뒤에 지운다. 여기서 같이 지우면 두 번 지우게 되고, 두 번째가
     * `FILE_NOT_FOUND` 로 터져 삭제 전체가 롤백된다.
     */
    @Override
    public void handleDeleteImageBefore(ObjectStorageFile file) {
        User user = userService.getUserByImageFileId(file.getFileId())
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        user.setImageFile(null);
    }

    @Override
    @Transactional(readOnly = true)
    public StorageFileData handleNoImageData(FileCategory category, String key) {
        ClassPathResource resource = new ClassPathResource("img/" + DEFAULT_PROFILE_IMAGE);
        try (InputStream inputStream = resource.getInputStream()) {
            return new StorageFileData(
                DEFAULT_PROFILE_IMAGE,
                MediaType.parseMediaType(DEFAULT_PROFILE_IMAGE_MIME),
                StreamUtils.copyToByteArray(inputStream)
            );
        } catch (IOException e) {
            log.error(e.getMessage());
            e.printStackTrace();
            throw new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
    
}
