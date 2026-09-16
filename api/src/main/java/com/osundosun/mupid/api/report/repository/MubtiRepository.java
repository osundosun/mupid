package com.osundosun.mupid.api.report.repository;

import java.util.Optional;

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
     */
    Optional<Mubti> findByClassAAndClassBAndClassCAndClassD(
        MubtiClass.A classA,
        MubtiClass.B classB,
        MubtiClass.C classC,
        MubtiClass.D classD
    );

}
