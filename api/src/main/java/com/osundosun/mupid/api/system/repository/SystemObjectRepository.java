package com.osundosun.mupid.api.system.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.system.entity.SystemObject;

public interface SystemObjectRepository extends JpaRepository<SystemObject, Integer> {

    Optional<SystemObject> findByStorageObjectKey(String storageObjectKey);

    List<SystemObject> findByModelVersion_ModelVersionId(int modelVersionId);

    List<SystemObject> findByModelVersion_ModelVersionIdIn(Collection<Integer> modelVersionIds);

}
