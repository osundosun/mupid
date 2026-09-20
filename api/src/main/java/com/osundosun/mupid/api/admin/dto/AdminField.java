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
    String relation,

    /**
     * 연관 필드라면, **대상 자원에서 그 개체를 알아보게 하는 컬럼**의 이름. 아니면 null.
     *
     * <p>FK 를 번호만 보여 주면 어느 행을 가리키는지 알 수 없다. 그래서 목록 응답은
     * 행마다 `_labels` 를 함께 내보내고, 그 값은 이 컬럼에서 읽는다. 무엇을 대표값으로
     * 쓸지는 서버가 정한다 — 대상 자원마다 다르고(회원=이메일, 아티스트=이름,
     * 앨범=제목) 메타모델에서 유도할 수 없는 도메인 판단이기 때문이다
     * (`AdminResourceRegistry.REPRESENTATIVE_FIELDS`).
     *
     * <p>대상 자원에 대표 컬럼이 정해져 있지 않으면 null 이고, 그때 화면은 지금처럼
     * 번호만 그린다.
     */
    String representativeField,

    /**
     * 연관 필드라면, **대상이 작은 이미지를 가질 수 있는가**. 아니면 false.
     *
     * <p>대상이 파일 자체(`ObjectStorageFile`)이거나 이미지 필드를 들고 있으면 true 다.
     * 화면은 이 값으로 "이미지가 없다"(`?` 자리)와 "이미지라는 개념이 없다"(아무것도 안
     * 그린다)를 가른다 — 둘을 같은 빈칸으로 그리면 이미지가 없는 것인지 그런 필드가 아닌지
     * 알 수 없어 화면이 거짓말을 한다.
     */
    boolean thumbnailable

) {
}
