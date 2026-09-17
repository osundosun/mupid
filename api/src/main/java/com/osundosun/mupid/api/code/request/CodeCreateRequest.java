package com.osundosun.mupid.api.code.request;

import com.osundosun.mupid.api.code.enumerate.CodeType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 코드 한 개를 추가한다.
 *
 * `codes` 는 `(codeType, codeName)` 이 UNIQUE 라 같은 값을 두 번 보내도 결과가 같다.
 * 배치가 재시도해도 안전하다.
 *
 * @param codeType 장르·분위기·악기 중 하나
 * @param codeName 표시 이름. 같은 종류 안에서 고유하다
 */
public record CodeCreateRequest(

    @NotNull(message = "코드 종류는 필수입니다.")
    CodeType codeType,

    @NotBlank(message = "코드 이름은 필수입니다.")
    @Size(max = 50, message = "코드 이름은 50자를 넘을 수 없습니다.")
    String codeName

) {
}
