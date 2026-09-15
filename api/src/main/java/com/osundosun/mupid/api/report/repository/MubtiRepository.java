package com.osundosun.mupid.api.report.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;

public interface MubtiRepository extends JpaRepository<Mubti, Integer> {

    /**
     * 네 축의 글자로 유형 하나를 찾는다. 16행이 전부 들어 있으면 반드시 하나가 나온다.
     *
     * 비어 있으면 리포트가 503 이다 (`REPORT_MUBTI_NOT_SEEDED`). 이 표는 사람이
     * 넣는다 — `ddl-auto` 가 `none` 이라 앱이 기준 데이터를 만들지 않는다
     * (decision/0011). 적용 스크립트는 `knowledge/domain/seed-mubti.sql`.
     */
    Optional<Mubti> findByClassAAndClassBAndClassCAndClassD(
        MubtiClass.A classA,
        MubtiClass.B classB,
        MubtiClass.C classC,
        MubtiClass.D classD
    );

}
