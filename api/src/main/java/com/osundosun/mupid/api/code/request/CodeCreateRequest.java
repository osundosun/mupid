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
 * @param codeType    장르·분위기·악기 중 하나
 * @param codeName    같은 종류 안에서 고유한 값. **AI 색인과 맞추는 값이다**
 * @param displayName 화면에 보여줄 이름. 비우면 `codeName` 으로 채운다
 */
public record CodeCreateRequest(

    @NotNull(message = "코드 종류는 필수입니다.")
    CodeType codeType,

    @NotBlank(message = "코드 이름은 필수입니다.")
    @Size(max = 50, message = "코드 이름은 50자를 넘을 수 없습니다.")
    String codeName,

    /**
     * 화면 표시명. **선택 항목으로 둔다.**
     *
     * <p>이 필드가 없던 시절의 호출(배치의 옛 버전·Bruno 컬렉션)이 그대로 도는데,
     * 필수로 만들면 그것들이 전부 400 이 된다. 비면 {@code codeName} 과 같게 채운다.
     *
     * <p>{@code codes.display_name} 은 `VARCHAR(200)` 이다.
     */
    @Size(max = 200, message = "표시 이름은 200자를 넘을 수 없습니다.")
    String displayName

) {
}
