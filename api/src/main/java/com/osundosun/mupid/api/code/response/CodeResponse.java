package com.osundosun.mupid.api.code.response;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;

/**
 * 코드 한 개.
 *
 * `created` 는 이번 요청이 실제로 만들었는지 알려준다. 배치가 "몇 개를 새로
 * 넣었는가" 를 세려면 필요하고, 없으면 매번 전체 목록을 다시 받아 비교해야 한다.
 */
public record CodeResponse(
    Integer codeId,
    CodeType codeType,
    String codeName,
    boolean created
) {

    public static CodeResponse of(Code code, boolean created) {
        return new CodeResponse(code.getCodeId(), code.getCodeType(), code.getCodeName(), created);
    }

}
