package com.osundosun.mupid.api.storage.service;

import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import com.osundosun.mupid.api.storage.dto.StorageFileData;
import com.osundosun.mupid.api.storage.dto.StorageObjectStat;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

public interface StorageService {
    
    ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file);

    /**
     * mime 을 호출부가 지정하는 업로드.
     * 브라우저가 아닌 클라이언트는 Content-Type 을 신뢰할 수 없고,
     * object_storage_files.mime 이 varchar(15) 라 긴 값이 들어오면 INSERT 가 깨진다.
     */
    ObjectStorageFile uploadFile(FileType fileType, FileCategory category, String key, MultipartFile file, String mime);

    /**
     * 오브젝트가 실제로 있는지 HEAD 로만 확인한다. 본문은 받지 않는다.
     * 스프링을 거치지 않고 올라온 파일(nginx → 스토리지 직행)을 검증할 때 쓴다.
     */
    Optional<StorageObjectStat> statObject(String key);

    /**
     * 업로드 없이 메타데이터만 기록한다.
     * 파일 자체는 이미 스토리지에 올라와 있어야 한다.
     */
    ObjectStorageFile registerFile(
        FileType fileType, FileCategory category, String key,
        String originalFileName, String mime, int fileSizeBytes);

    StorageFileData getFileData(int fileId);

    void deleteFile(int fileId);

    Optional<ObjectStorageFile> getFile(int fileId);

    Optional<ObjectStorageFile> getFileByKey(String key);

    boolean isFileExists(String key);

}
