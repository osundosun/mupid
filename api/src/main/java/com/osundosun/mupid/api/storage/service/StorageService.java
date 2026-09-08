package com.osundosun.mupid.api.storage.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

public interface StorageService {
    
    ObjectStorageFile uploadFile(String key, MultipartFile file) throws IOException;

    StorageFileData getFileData(int fileId);

    void deleteFile(int fileId);

    Optional<ObjectStorageFile> getFile(int fileId);

    Optional<ObjectStorageFile> getFileByKey(String key);

    boolean isFileExists(String key);

}
