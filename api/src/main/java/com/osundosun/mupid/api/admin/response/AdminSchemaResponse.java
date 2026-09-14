package com.osundosun.mupid.api.admin.response;

import java.util.List;

import com.osundosun.mupid.api.admin.dto.AdminField;

/**
 * 자원 하나의 컬럼 구성. 화면은 이것만 보고 표와 입력 폼을 그린다.
 */
public record AdminSchemaResponse(

    String name,

    String label,

    String table,

    String category,

    boolean composite,

    /**
     * 식별자를 이루는 필드 이름. URL 의 `{id}` 는 이 순서대로 `,` 로 이어 붙인 것이다.
     */
    List<String> ids,

    List<AdminField> fields

) {
}
