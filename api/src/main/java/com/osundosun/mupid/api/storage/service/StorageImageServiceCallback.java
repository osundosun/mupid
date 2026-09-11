package com.osundosun.mupid.api.storage.service;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;

public interface StorageImageServiceCallback {
    
    void handleUploadImageAfter(ObjectStorageFile file);

    void handleDeleteImageBefore(ObjectStorageFile file);

    StorageFileData handleNoImageData(FileCategory category, String key);

}
