package com.osundosun.mupid.api.user.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileDto;

public interface UserImageService {
    
    void updateUserImage(int userId, MultipartFile imageFile) throws IOException;

    void deleteUserImage(int userId);

    StorageFileDto getUserImageData(int userId);
    
}
