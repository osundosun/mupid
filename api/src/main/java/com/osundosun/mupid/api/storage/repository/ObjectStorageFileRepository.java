package com.osundosun.mupid.api.storage.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

public interface ObjectStorageFileRepository extends JpaRepository<ObjectStorageFile, Integer> {

    ObjectStorageFile findByStorageObjectKey(String key);

}
