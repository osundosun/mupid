package com.osundosun.mupid.api.admin.dto;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Getter;

/**
 * 관리 대상 자원 하나(= 엔티티 하나)의 전부.
 *
 * 기동할 때 JPA 메타모델에서 한 번 만들어 두고 계속 재사용한다.
 * 엔티티가 늘면 자원도 저절로 는다 — 자원 목록을 손으로 관리하지 않는다.
 */
@Getter
@Builder
public class AdminResourceMeta {

    /**
     * URL 에 쓰는 이름. 엔티티 이름의 첫 글자를 내린 것 (`MusicComment` → `musicComment`).
     */
    private final String name;

    /**
     * 화면에 보일 한글 이름.
     */
    private final String label;

    /**
     * 실제 테이블 이름.
     */
    private final String table;

    /**
     * 왼쪽 목록에서 묶이는 분류.
     */
    private final String category;

    private final Class<?> javaType;

    /**
     * 엔티티에서 식별자를 담고 있는 속성 이름 (`userId`, `musicReactionId` …).
     */
    private final String idAttributeName;

    /**
     * 식별자 자체의 타입. 복합키면 임베더블 클래스다.
     */
    private final Class<?> idJavaType;

    /**
     * 엔티티에서 식별자 속성으로 가는 통로.
     */
    private final Field idMember;

    /**
     * `@EmbeddedId` 로 된 복합키인가.
     */
    private final boolean composite;

    /**
     * 식별자를 이루는 필드 이름들. 단일키면 하나, 복합키면 펼쳐진 구성 요소 전부.
     * URL 의 `{id}` 는 이 순서대로 `,` 로 이어 붙인 것이다.
     */
    private final List<String> idFieldNames;

    /**
     * 필드 이름 → 속성. 선언 순서를 지킨다 (LinkedHashMap).
     */
    private final Map<String, AdminAttribute> attributes;

    public AdminAttribute attribute(String fieldName) {
        return attributes.get(fieldName);
    }

    public List<AdminField> fields() {
        return attributes.values().stream().map(AdminAttribute::getField).toList();
    }

}
