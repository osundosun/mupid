package com.osundosun.mupid.api.storage.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;

public interface StorageImageService {

    int uploadImage(FileCategory category, String key, MultipartFile imageFile);

    StorageFileData getImageData(FileCategory category, String key);

    void deleteImage(FileCategory category, String key);

    String getStorageKey(FileCategory category, String key);

    /**
     * 화면이 그대로 부를 수 있는 조회 URL.
     *
     * `getStorageKey` 와 짝이다. 저장 키는 `image/album/커버` 처럼 접두사가
     * 붙어 있고, 조회 경로는 `/api/v1/storage/image/{CATEGORY}/{키}` 다.
     *
     * **카테고리는 대문자다.** 스프링의 enum 바인딩은 소문자도 받아 주지만,
     * `SecurityConfig` 의 공개 경로(`/api/v1/storage/image/ALBUM/*` 등)가
     * 대문자로 적혀 있어 소문자로 내보내면 비로그인 화면에서 401 이 된다.
     */
    static String imageUrl(FileCategory category, String key) {
        return "/api/v1/storage/image/%s/%s".formatted(
            category.name(),
            URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    /**
     * 오브젝트 스토리지의 저장 키. `image/{카테고리 소문자}/{키}`.
     *
     * <p><b>키 모양을 아는 곳은 여기 하나뿐이어야 한다.</b> 위 {@link #imageUrl} 이 이 모양을
     * 되짚어 조회 URL 을 만들기 때문이다 — 앞 두 마디를 떼고 카테고리를 붙인다.
     *
     * <p>그 약속을 지키지 않은 코드가 있었다. {@code BootstrapRunner} 가 키를
     * `mubti/VTFC.webp` 로 손수 만들면서 접두사를 빼는 바람에 {@code imageUrl} 이 3마디를
     * 못 찾아 <b>예외도 로그도 없이 null</b> 을 돌려주었고, `/report` 의 MuBTI 캐릭터가
     * 뜨지 않았다. 그래서 그쪽도 이 메서드를 쓴다.
     */
    static String storageKey(FileCategory category, String key) {
        return "image/%s/%s".formatted(category.name().toLowerCase(), key);
    }

    /**
     * 저장된 파일의 조회 URL. 파일이 없거나 삭제됐으면 `null` 이다.
     *
     * 없을 때 기본 이미지를 보여 주고 싶으면 호출 쪽이
     * `imageUrl(category, "default")` 를 쓴다 — 그 키에 파일이 없으면 카테고리
     * 콜백(`handleNoImageData`)이 기본 이미지를 준다.
     */
    static String imageUrl(ObjectStorageFile file) {
        if (file == null || Boolean.TRUE.equals(file.getIsDeleted())) return null;
        // 저장 키는 `image/{카테고리}/{키}` 다. 앞 두 마디를 떼어 낸다.
        String[] parts = file.getStorageObjectKey().split("/", 3);
        if (parts.length < 3) return null;
        return imageUrl(file.getCategory(), parts[2]);
    }

}
