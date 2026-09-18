package com.osundosun.mupid.api.code.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;

public interface CodeRepository extends JpaRepository<Code, Integer> {

    /** 화면에 뿌릴 목록. 등록 순서를 그대로 쓴다. */
    List<Code> findAllByCodeTypeOrderByCodeIdAsc(CodeType codeType);

    List<Code> findAllByCodeIdIn(List<Integer> codeIds);

    /** 리포트 키워드의 코드명에 대응하는 화면 표시명을 한 번에 읽는다. */
    List<Code> findAllByCodeTypeAndCodeNameIn(CodeType codeType, Set<String> codeNames);

    /**
     * 종류와 이름으로 하나.
     *
     * 표의 UNIQUE 키가 `(code_type, code_name)` 이라 최대 하나다. 코드 추가가
     * 멱등이어야 해서 필요하다 — 배치가 같은 값을 두 번 보내도 새로 만들지 않는다.
     */
    Optional<Code> findByCodeTypeAndCodeName(CodeType codeType, String codeName);

}
