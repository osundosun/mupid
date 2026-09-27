package com.osundosun.mupid.api.report.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;

public interface MubtiRepository extends JpaRepository<Mubti, Integer> {

    /**
     * 네 축의 글자로 유형 하나를 찾는다. 16행이 전부 들어 있으면 반드시 하나가 나온다.
     *
     * 비어 있으면 리포트가 503 이다 (`REPORT_MUBTI_NOT_SEEDED`). 이 표는 기동 때
     * `bootstrap/BootstrapRunner` 가 채운다 — `image_file_id` 가 NOT NULL 이라
     * 캐릭터 이미지를 스토리지에 올린 뒤 그 `file_id` 를 물려야 하기 때문이다.
     *
     * <p><b>이미지 행을 함께 읽는다</b> (S15P21A204-463). 리포트가 이 행으로 이미지 URL 을
     * 만드는데(`StorageImageService.imageUrl`), `imageFile` 은 지연 로딩이다. 화면 요청은
     * open-in-view 가 세션을 열어 두어 가려지지만, 00:05 전날 리포트 배치는 트랜잭션 밖이라
     * `LazyInitializationException` 으로 저장이 통째로 실패했다(2026-09-26 분 대상 3명 중 저장 0).
     * 집계는 ClickHouse 를 오래 부르므로 트랜잭션으로 감싸 커넥션을 붙잡는 대신 여기서 끌어온다.
     */
    @EntityGraph(attributePaths = "imageFile")
    Optional<Mubti> findByClassAAndClassBAndClassCAndClassD(
        MubtiClass.A classA,
        MubtiClass.B classB,
        MubtiClass.C classC,
        MubtiClass.D classD
    );

}
