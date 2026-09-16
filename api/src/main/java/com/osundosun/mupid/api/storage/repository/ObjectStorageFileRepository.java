package com.osundosun.mupid.api.storage.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

public interface ObjectStorageFileRepository extends JpaRepository<ObjectStorageFile, Integer> {

    Optional<ObjectStorageFile> findByStorageObjectKeyAndIsDeletedFalse(String key);

    /**
     * 저장 키를 갱신한다. 바뀐 행 수를 돌려준다 (0 이면 그 키의 행이 없다).
     *
     * <p>엔티티를 읽어 set 하는 대신 한 문장으로 보내는 이유는 <b>기동 시점</b>에 쓰기 때문이다.
     * {@code ApplicationRunner} 에는 트랜잭션이 없어 지연 로딩 연관을 건드릴 수 없고
     * (`Mubti.imageFile` 때문에 실제로 {@code LazyInitializationException} 을 겪었다),
     * 읽고-고치는 사이에 다른 요청이 끼어들 여지도 없애는 편이 낫다.
     *
     * <p>`@Modifying` 은 이 메서드를 <b>트랜잭션 안에서만</b> 부를 수 있게 한다. 부르는 쪽이
     * 경계를 열어야 한다 — 그래서 이 호출은 {@code StorageService} 를 거친다.
     */
    @Modifying
    @Query("update ObjectStorageFile f set f.storageObjectKey = :wanted "
        + "where f.storageObjectKey = :stale")
    int renameStorageObjectKey(@Param("stale") String stale, @Param("wanted") String wanted);

}
