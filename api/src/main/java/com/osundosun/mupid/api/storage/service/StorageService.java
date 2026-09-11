package com.osundosun.mupid.api.storage.service;

import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

public interface StorageService {
    
    ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file);

    StorageFileData getFileData(int fileId);

    void deleteFile(int fileId);

    Optional<ObjectStorageFile> getFile(int fileId);

    Optional<ObjectStorageFile> getFileByKey(String key);

    boolean isFileExists(String key);

}
