package com.osundosun.mupid.api.storage.service;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;

public interface StorageImageService {

    int uploadImage(FileCategory category, String key, MultipartFile imageFile);

    StorageFileData getImageData(FileCategory category, String key);

    void deleteImage(FileCategory category, String key);

    String getStorageKey(FileCategory category, String key);
    
}
