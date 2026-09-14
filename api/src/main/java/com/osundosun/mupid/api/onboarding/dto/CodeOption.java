package com.osundosun.mupid.api.onboarding.dto;

import com.osundosun.mupid.api.code.entity.Code;

/**
 * 온보딩에서 고를 수 있는 항목 하나.
 *
 * 화면은 `name` 을 보여주고 `codeId` 를 되돌려 준다. 이름이 바뀌어도 저장된
 * 응답이 흔들리지 않게 번호를 기준으로 주고받는다.
 */
public record CodeOption(
    Integer codeId,
    String name
) {

    public static CodeOption of(Code code) {
        return new CodeOption(code.getCodeId(), code.getCodeName());
    }

}
