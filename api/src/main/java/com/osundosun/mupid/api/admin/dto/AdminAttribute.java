package com.osundosun.mupid.api.admin.dto;

import java.lang.reflect.Field;

import lombok.Builder;
import lombok.Getter;

/**
 * 화면에 보내는 {@link AdminField} 에, 값을 실제로 읽고 쓰는 데 필요한 것을 붙인 내부용 묶음.
 *
 * 리플렉션 {@link Field} 를 직접 들고 있는 까닭은 두 가지다.
 *
 * 1. 게터를 거치면 `@ManyToOne(LAZY)` 프록시가 깨어나 목록 한 화면에 N 번의 추가 쿼리가 붙는다.
 *    필드를 그대로 읽으면 프록시 객체를 얻고, 식별자는 `PersistenceUnitUtil` 로 꺼낼 수 있다.
 * 2. 엔티티의 기본 생성자가 `protected` 라 어차피 리플렉션이 필요하다.
 */
@Getter
@Builder
public class AdminAttribute {

    /**
     * 화면 쪽 표현.
     */
    private final AdminField field;

    /**
     * 값을 읽고 쓰는 통로. 임베디드 식별자의 구성 요소면 임베더블 클래스의 필드다.
     */
    private final Field member;

    /**
     * 자바 타입. 연관 필드면 **대상 엔티티의 식별자 타입**이다.
     */
    private final Class<?> valueType;

    /**
     * 연관 필드가 가리키는 엔티티 클래스. 아니면 null.
     */
    private final Class<?> relationType;

    /**
     * `@EmbeddedId` 안에 들어 있는 구성 요소인가.
     */
    private final boolean insideId;

    /**
     * 명시된 컬럼 이름 (`@Column(name=...)` · `@JoinColumn(name=...)`). 없으면 null.
     *
     * 복합키 구성 요소가 `insertable = false` 인 엔티티(`MusicGenre` 등)를 만들 때,
     * 같은 컬럼을 쓰는 연관 필드에서 식별자 값을 끌어오는 데 쓴다. 그 엔티티들은
     * 식별자 값을 연관 쪽이 채우게 매핑돼 있어서, 이 연결이 없으면 식별자가 null 인 채로
     * persist 되어 터진다.
     */
    private final String columnName;

    public String name() {
        return field.name();
    }

}
