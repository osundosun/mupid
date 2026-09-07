package com.osundosun.mupid.api.storage.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileDto;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

public interface StorageService {
    
    ObjectStorageFile uploadFile(String key, MultipartFile file) throws IOException;

    StorageFileDto getFileData(int fileId);

    void deleteFile(int fileId);

    ObjectStorageFile getFile(int fileId);

    boolean isFileExists(String key);

}
