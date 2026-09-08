package com.osundosun.mupid.api.storage.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

public interface ObjectStorageFileRepository extends JpaRepository<ObjectStorageFile, Integer> {

    Optional<ObjectStorageFile> findByStorageObjectKeyAndIsDeletedFalse(String key);

}
