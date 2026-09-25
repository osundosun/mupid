package com.osundosun.mupid.api.system.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.system.entity.ModelVersion;

import jakarta.persistence.LockModeType;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Integer> {

    Optional<ModelVersion> findByNameAndVersion(String name, String version);

    /**
     * 행을 잠그고 읽는다. 같은 버전의 파일 둘이 동시에 완료될 때 둘 다 "아직 하나 남았다"
     * 고 보고 지나가면 버전이 영영 후보가 되지 못한다. 버전 행에서 줄을 세운다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from ModelVersion v where v.modelVersionId = :id")
    Optional<ModelVersion> findByIdForUpdate(@Param("id") int id);

    List<ModelVersion> findByNameOrderByModelVersionIdDesc(String name);

}
