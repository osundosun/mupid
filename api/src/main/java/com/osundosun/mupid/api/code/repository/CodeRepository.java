package com.osundosun.mupid.api.code.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;

public interface CodeRepository extends JpaRepository<Code, Integer> {

    /** 화면에 뿌릴 목록. 등록 순서를 그대로 쓴다. */
    List<Code> findAllByCodeTypeOrderByCodeIdAsc(CodeType codeType);

    List<Code> findAllByCodeIdIn(List<Integer> codeIds);

}
