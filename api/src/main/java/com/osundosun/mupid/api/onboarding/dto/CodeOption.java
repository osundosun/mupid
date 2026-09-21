package com.osundosun.mupid.api.onboarding.dto;

import com.osundosun.mupid.api.code.entity.Code;

/**
 * 온보딩에서 고를 수 있는 항목 하나.
 *
 * 화면은 `displayName` 을 보여주고 `codeId` 를 되돌려 준다. 이름이 바뀌어도 저장된
 * 응답이 흔들리지 않게 번호를 기준으로 주고받는다.
 *
 * **`name` 은 화면용이 아니라 AI 로 넘길 값이다.** `AiRecommendToolImpl` 이
 * `CodeOption::name` 을 그대로 실어 보내므로, 여기를 표시명으로 바꾸면
 * 색인에 없는 말이 가서 **씨앗이 0 이 된다**(오류는 안 나고 개인화만 사라진다).
 * 그래서 둘을 갈라 둔다.
 *
 * @param codeId      되돌려 받을 코드 번호
 * @param name        영문 내부 코드 (`codeName`). AI 색인과 맞추는 값이다. **여기를 바꾸지 않는다**
 * @param displayName 화면에 그릴 한국어 이름. 아직 표시명이 없는 행은 `name` 과 같다
 */
public record CodeOption(
    Integer codeId,
    String name,
    String displayName
) {

    /**
     * `display_name` 이 비어 있으면 `codeName` 으로 대신한다.
     *
     * <p>컬럼은 `NOT NULL` 이지만 **화면이 빈 칸을 그리는 일이 없도록** 여기서도
     * 한 번 더 막는다. DDL 을 적용하기 전에 읽은 행이나 손으로 넣은 행이 그럴 수 있다.
     */
    public static CodeOption of(Code code) {
        String shown = code.getDisplayName();
        if (shown == null || shown.isBlank()) {
            shown = code.getCodeName();
        }
        return new CodeOption(code.getCodeId(), code.getCodeName(), shown);
    }

}
