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

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/storage/image/{category}/{storageKey}")
@RequiredArgsConstructor
public class StorageImageController {

    private final StorageImageService storageImageService;
    
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<ImageUploadResponse>> uploadImage(
        Authentication auth,
        @PathVariable FileCategory category,
        @PathVariable String storageKey,
        @RequestParam MultipartFile imageFile
    ) {
        int fileId = storageImageService.uploadImage(category, storageKey, imageFile);
        return ResponseEntity.ok(BaseResponse.of(
            "이미지가 업로드 되었습니다.",
            new ImageUploadResponse(fileId)
        ));
    }

    @GetMapping
    public ResponseEntity<byte[]> getImageData(
        @PathVariable FileCategory category,
        @PathVariable String storageKey
    ) {
        StorageFileData imageData = storageImageService.getImageData(category, storageKey);
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

    @DeleteMapping
    public ResponseEntity<BaseResponse<?>> deleteImage(
        @PathVariable FileCategory category,
        @PathVariable String storageKey
    ) {
        storageImageService.deleteImage(category, storageKey);
        return ResponseEntity.ok(BaseResponse.message("이미지가 삭제 되었습니다."));
    }

}
