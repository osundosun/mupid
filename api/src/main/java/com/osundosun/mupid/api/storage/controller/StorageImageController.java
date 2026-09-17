package com.osundosun.mupid.api.storage.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.response.ImageUploadResponse;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 이미지 저장소.
 *
 * <h2>조회만 경로 모양이 다르다</h2>
 *
 * 쓰기(업로드·삭제)는 <b>자리를 지정</b>하는 일이라 카테고리와 키가 필요하다 —
 * {@code /{category}/{storageKey}}. 반면 읽기는 <b>이미 존재하는 파일</b>을 가리키는
 * 일이라 파일 번호 하나로 충분하다 — {@code /{fileId}}.
 *
 * <p>한때 조회도 저장 키로 했다. 그때는 조회 URL 이 저장 키를 되짚어 만들어져서 키
 * 모양이 곧 조회 계약이었고, 키를 손으로 만든 코드 하나 때문에 {@code /report} 의
 * MuBTI 캐릭터가 <b>예외도 로그도 없이</b> 뜨지 않았다. 번호로 읽으면 그 종류의 사고가
 * 아예 생기지 않는다 — 조회가 키 모양을 몰라도 되기 때문이다.
 *
 * <p>번호는 순번이라 추측할 수 있다. 그래서 읽을 때 <b>파일 종류가 이미지인지</b>와
 * <b>그 카테고리를 공개해도 되는지</b>를 서비스가 본다
 * ({@code StorageImageServiceImpl.getImageData}).
 *
 * <h2>기본 프로필 이미지가 경로로 남아 있는 이유</h2>
 *
 * 그 이미지는 파일이 아니라 classpath 자산이라 파일 번호가 없다. 번호로 가리킬 수 없어
 * {@code /PROFILE/default} 라는 자리를 따로 둔다. 프런트는 이 주소를 "사진 없음"의
 * 표시로 쓴다({@code frontend/lib/profile-photo.ts}).
 */
@RestController
@RequestMapping("/api/v1/storage/image")
@RequiredArgsConstructor
public class StorageImageController {

    /** 기본 프로필 이미지의 키. 이 키에는 파일이 없다. */
    private static final String DEFAULT_PROFILE_KEY = "default";

    private final StorageImageService storageImageService;

    /**
     * 이미지 업로드.
     *
     * <p>멀티파트 필드 이름은 {@code imageFile} 로 camelCase 다. {@code SNAKE_CASE} 설정은
     * JSON 본문에만 걸리고 {@code @RequestParam} 이름은 그대로 쓰인다.
     *
     * <p>자리에 쓸 권한이 있는지는 카테고리 정책이 판단한다
     * ({@code FileCategory.accessPolicy}) — 프로필은 본인 번호만, 나머지는
     * {@code ROLE_SYSTEM} 이상만.
     */
    @PostMapping(value = "/{category}/{storageKey}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<ImageUploadResponse>> uploadImage(
        Authentication auth,
        @PathVariable FileCategory category,
        @PathVariable String storageKey,
        @RequestParam MultipartFile imageFile
    ) {
        User actor = (User)auth.getPrincipal();
        int fileId = storageImageService.uploadImage(category, storageKey, imageFile, actor);
        return ResponseEntity.ok(BaseResponse.of(
            "이미지가 업로드 되었습니다.",
            new ImageUploadResponse(fileId)
        ));
    }

    /**
     * 파일 번호로 이미지 조회.
     *
     * <p>경로 변수를 숫자로 좁힌 정규식({@code [0-9]+})이 있는 것은 아래
     * {@code /PROFILE/default} 와 겹치지 않게 하기 위해서다. 없으면 스프링이
     * {@code PROFILE} 을 번호로 바인딩하려다 실패한다.
     *
     * <p>공개 카테고리({@code ALBUM}·{@code ARTIST}·{@code TRACK}·{@code MUBTI})는
     * 토큰 없이도 열린다. 목록의 표지를 {@code <img src>} 로 그리는데 거기에는
     * {@code Authorization} 헤더를 붙일 수 없기 때문이다. 프로필 사진과 스트리밍
     * 청크는 개인 정보·내부 자원이라 토큰이 있어야 한다 — 그 판단은 서비스가 한다.
     */
    @GetMapping("/{fileId:[0-9]+}")
    public ResponseEntity<byte[]> getImageData(@PathVariable int fileId) {
        return imageResponse(storageImageService.getImageData(fileId));
    }

    /**
     * 기본 프로필 이미지 조회. 파일이 아니라 classpath 자산이라 번호가 없다.
     *
     * <p>{@code PROFILE} 이 비공개 카테고리인데도 이 경로를 여는 것은, 이 그림이
     * <b>특정 사람의 사진이 아니라 자리를 채우는 기본값</b>이기 때문이다. 사진을 올린
     * 적 없는 계정의 {@code image_url} 이 이 주소를 가리키므로, 닫으면 그 사람들의
     * 프로필 자리가 빈 채로 남는다.
     */
    @GetMapping("/PROFILE/" + DEFAULT_PROFILE_KEY)
    public ResponseEntity<byte[]> getDefaultProfileImage() {
        return imageResponse(storageImageService.getDefaultProfileImage());
    }

    /**
     * 이미지 삭제.
     *
     * <p>권한은 업로드와 <b>같은 정책</b>을 본다. 한쪽만 잠그면 잠그지 않은 쪽으로
     * 우회된다 — 예전에는 프로필만 소유자 검사가 있어서 나머지 카테고리는 로그인만
     * 하면 누구나 남의 표지를 지울 수 있었다.
     */
    @DeleteMapping("/{category}/{storageKey}")
    public ResponseEntity<BaseResponse<?>> deleteImage(
        Authentication auth,
        @PathVariable FileCategory category,
        @PathVariable String storageKey
    ) {
        User actor = (User)auth.getPrincipal();
        storageImageService.deleteImage(category, storageKey, actor);
        return ResponseEntity.ok(BaseResponse.message("이미지가 삭제 되었습니다."));
    }

    /**
     * 이미지 바이트 응답.
     *
     * <p>{@code BaseResponse} 로 감싸지 않는다 — 이미지 태그가 그대로 받아야 하기
     * 때문이다. 저장된 MIME 을 {@code Content-Type} 에 그대로 쓰고, 원본 파일명은
     * {@code Content-Disposition} 에 URL 인코딩해 붙인다.
     */
    private ResponseEntity<byte[]> imageResponse(StorageFileData imageData) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(imageData.mediaType());
        headers.add(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"%s\"".formatted(imageData.getEncodedFilename())
        );
        return ResponseEntity.ok()
                    .headers(headers)
                    .body(imageData.data());
    }

}
