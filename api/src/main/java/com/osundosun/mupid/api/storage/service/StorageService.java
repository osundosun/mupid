package com.osundosun.mupid.api.storage.service;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.StorageFileCategory;
import com.osundosun.mupid.api.storage.enumerate.StorageFileType;

public interface StorageService {

    ObjectStorageFile uploadFile(
        String key,
        MultipartFile file,
        StorageFileType fileType,
        StorageFileCategory category
    ) throws IOException;

    StorageFileData getFileData(int fileId);

    void deleteFile(int fileId);

    Optional<ObjectStorageFile> getFile(int fileId);

    Optional<ObjectStorageFile> getFileByKey(String key);

    boolean isFileExists(String key);

}
