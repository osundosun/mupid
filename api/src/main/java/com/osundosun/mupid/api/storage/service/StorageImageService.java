package com.osundosun.mupid.api.storage.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.user.entity.User;

public interface StorageImageService {

    int uploadImage(FileCategory category, String key, MultipartFile imageFile, User actor);

    /**
     * 파일 번호로 이미지 바이트를 읽는다.
     *
     * <p>한때 저장 키로 읽었다. 그때는 조회 URL({@code imageUrl})이 저장 키를 되짚어
     * 만들어져서 <b>키 모양이 곧 조회 계약</b>이었고, 키를 손으로 만든 코드 하나 때문에
     * {@code /report} 의 캐릭터가 조용히 뜨지 않았다. 번호로 읽으면 조회가 키 모양과
     * 무관해진다 — 그 사고의 종류 자체가 사라진다.
     */
    StorageFileData getImageData(int fileId);

    /**
     * 사진을 올린 적 없는 사람에게 내려 주는 기본 프로필 이미지.
     *
     * <p>파일이 아니라 classpath 자산이라 파일 번호가 없다. 그래서 조회가 번호를 받는
     * 뒤로도 이 자리만은 별도 경로({@code GET /api/v1/storage/image/PROFILE/default})로
     * 남는다. 프런트도 그 경로를 "사진 없음"의 표시로 쓴다
     * ({@code frontend/lib/profile-photo.ts}).
     */
    StorageFileData getDefaultProfileImage();

    void deleteImage(FileCategory category, String key, User actor);

    String getStorageKey(FileCategory category, String key);

    /**
     * 파일 하나의 조회 URL.
     *
     * <p>저장 키에서 유도되는 <b>단 하나의 자리</b>다. 키 자체는 {@link #storageKey} 가
     * 만들고, 여기서는 번호를 붙이기만 한다. 두 자리가 갈라지면 화면이 조용히 빈 자리를
     * 그린다 — 실제로 그랬다({@code /report} 의 MuBTI 캐릭터).
     */
    static String imageUrl(FileCategory category, String key) {
        return "/api/v1/storage/image/%s/%s".formatted(
            category.name(),
            URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    /**
     * 오브젝트 스토리지의 저장 키. `image/{카테고리 소문자}/{키}`.
     *
     * <p><b>키 모양을 아는 곳은 여기 하나뿐이어야 한다.</b> 부트스트랩이 시드 이미지를
     * 올릴 때도 이 메서드를 쓴다.
     */
    static String storageKey(FileCategory category, String key) {
        return "image/%s/%s".formatted(category.name().toLowerCase(), key);
    }

    /**
     * 행 하나의 조회 URL. 이미지가 아니거나 지워졌으면 {@code null} 이다.
     *
     * <p><b>번호로 만든다.</b> 저장 키를 되짚던 때는 키 모양이 어긋나면 예외도 로그도 없이
     * {@code null} 이 되어 화면이 조용히 빈 자리를 그렸다. 이제 조회는 번호를 받으므로
     * 키 모양이 어긋나도 URL 은 제대로 나오고, 그 파일이 이미지인지만 본다.
     *
     * <p>{@code null} 일 때 기본 이미지를 보여 주고 싶으면 호출 쪽이
     * {@link #imageUrl(FileCategory, String)} 으로 {@code PROFILE/default} 를 가리킨다.
     */
    static String imageUrl(ObjectStorageFile file) {
        if (file == null || file.getFileId() == null) return null;
        if (Boolean.TRUE.equals(file.getIsDeleted())) return null;
        if (file.getFileType() != FileType.IMG) return null;
        return "/api/v1/storage/image/%d".formatted(file.getFileId());
    }

    /**
     * 사진을 올린 적 없는 계정이 가리키는 기본 프로필 이미지 주소.
     *
     * <p>그 경로에는 파일이 없고 서버가 classpath 의 기본 이미지를 돌려주므로,
     * <b>항상 받아올 것이 있는 URL</b> 이다. 화면은 {@code null} 검사 없이 그대로 쓴다.
     */
    static String defaultProfileImageUrl() {
        return imageUrl(FileCategory.PROFILE, "default");
    }

}
