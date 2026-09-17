package com.osundosun.mupid.api.code.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.code.request.CodeCreateRequest;
import com.osundosun.mupid.api.code.response.CodeResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CodeServiceImpl implements CodeService {

    private final CodeRepository codeRepository;

    /**
     * 같은 `(종류, 이름)` 이 있으면 만들지 않고 그것을 돌려준다.
     *
     * <p>배치가 곡마다 분류를 올리면서 같은 장르를 수백 번 보낸다. 그때마다 409 를
     * 돌려주면 호출하는 쪽이 "이미 있음" 과 "진짜 실패" 를 가려야 하고, 그 분기를
     * 빠뜨리면 배치가 통째로 멈춘다. 멱등하게 두는 편이 안전하다.
     *
     * <p>먼저 찾고 없으면 넣는데, 그 사이에 다른 요청이 같은 값을 넣을 수 있다.
     * 표에 UNIQUE 가 걸려 있어 그때는 제약 위반이 나므로 <b>한 번 더 찾아서</b>
     * 그 행을 돌려준다. 여기서 예외를 그대로 올리면 동시에 돈 배치 하나가 이유
     * 없이 실패한다.
     */
    @Override
    @Transactional
    public CodeResponse addCode(CodeCreateRequest request) {
        String name = request.codeName().trim();

        return codeRepository.findByCodeTypeAndCodeName(request.codeType(), name)
            .map(code -> CodeResponse.of(code, false))
            .orElseGet(() -> insert(request, name));
    }

    private CodeResponse insert(CodeCreateRequest request, String name) {
        try {
            Code saved = codeRepository.saveAndFlush(
                Code.builder().codeType(request.codeType()).codeName(name).build());
            return CodeResponse.of(saved, true);
        } catch (DataIntegrityViolationException duplicated) {
            // 같은 값을 동시에 넣었다. 이미 있는 것을 돌려주면 결과는 같다.
            return codeRepository.findByCodeTypeAndCodeName(request.codeType(), name)
                .map(code -> CodeResponse.of(code, false))
                .orElseThrow(() -> duplicated);
        }
    }

}
