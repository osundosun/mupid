package com.osundosun.mupid.api.admin.dto;

import java.util.List;

/**
 * 관리자 화면이 컬럼 하나를 그리는 데 필요한 전부.
 *
 * JPA 메타모델에서 뽑아낸 것이라 엔티티가 바뀌면 자동으로 따라온다.
 * 화면에 하드코딩된 컬럼 목록이 없다는 뜻이다.
 */
public record AdminField(

    /**
     * 행(row) Map 의 키이자 정렬·필터 파라미터에 쓰는 이름.
     * 임베디드 식별자의 구성 요소는 바깥으로 펼쳐져 제 이름을 그대로 쓴다.
     */
    String name,

    /**
     * 화면에 보일 이름. 지금은 필드명 그대로다.
     */
    String label,

    /**
     * string / text / number / boolean / datetime / date / time / enum / relation / other
     */
    String type,

    /**
     * 식별자를 이루는 필드인가.
     */
    boolean id,

    /**
     * null 을 허용하는가.
     */
    boolean nullable,

    /**
     * 생성 폼에서 넣을 수 있는가. `insertable = false` 컬럼은 false 다.
     */
    boolean insertable,

    /**
     * 수정 폼에서 고칠 수 있는가. `updatable = false` 컬럼과 식별자는 false 다.
     */
    boolean updatable,

    /**
     * DB 가 만들어 주는 값인가 (`@GeneratedValue`). 생성 폼에서 숨긴다.
     */
    boolean generated,

    /**
     * 값을 밖으로 내보내면 안 되는 필드인가 (비밀번호 해시 등).
     * 조회 응답에서는 언제나 null 로 나가고, 쓰기만 할 수 있다.
     */
    boolean sensitive,

    /**
     * enum 이라면 고를 수 있는 값 전부. 아니면 null.
     */
    List<String> options,

    /**
     * 연관 필드라면 가리키는 자원 이름. 아니면 null.
     * 값 자체는 대상 엔티티가 아니라 **대상의 식별자**다 (프록시를 깨우지 않는다).
     */
    String relation

) {
}
