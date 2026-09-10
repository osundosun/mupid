package com.osundosun.mupid.api.user.controller;

import java.io.IOException;

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
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.service.UserImageService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/user/image")
@RequiredArgsConstructor
public class UserImageController {

    private final UserImageService userImageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BaseResponse<Void>> updateUserImage(
        Authentication auth,
        @RequestParam MultipartFile file
    ) throws IOException {
        User user = (User)auth.getPrincipal();
        userImageService.updateUserImage(user.getUserId(), file);
        return ResponseEntity.ok(
            BaseResponse.message("사용자 프로필 사진이 수정되었습니다.")
        );
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> deleteUserImage(
        Authentication auth
    ) {
        User user = (User)auth.getPrincipal();
        userImageService.deleteUserImage(user.getUserId());
        return ResponseEntity.ok(
            BaseResponse.message("사용자 프로필 사진이 삭제되었습니다.")
        );
    }
    
    @GetMapping("/{userId}")
    public ResponseEntity<byte[]> getUserImage(
        @PathVariable int userId
    ) {
        StorageFileData storageFile = userImageService.getUserImageData(userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(storageFile.mediaType());
        headers.add(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"%s\"".formatted(storageFile.getEncodedFilename())
        );
        return ResponseEntity.ok()
                    .headers(headers)
                    .body(storageFile.data());
    }

}
