package com.osundosun.mupid.api.admin.dto;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import com.osundosun.mupid.api.admin.dto.AdminChildLink;
import com.osundosun.mupid.api.admin.dto.AdminTimelineAxis;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

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

    /**
     * 이 자원의 행에서 자식 자원으로 건너가는 링크 ("이 앨범의 음악들"). 없으면 빈 목록이다.
     *
     * <p>기동할 때는 빈 목록으로 만들고, 모든 자원이 만들어진 **뒤에**
     * {@code AdminResourceRegistry.validateChildLinks} 가 채운다 — 선언이 실제 관계와
     * 맞는지 보려면 자식 자원의 속성 표가 이미 있어야 하기 때문이다.
     */
    @Setter
    @Builder.Default
    private List<AdminChildLink> childLinks = List.of();

    /**
     * 이 자원의 행을 **시간축 위에 가로로** 그릴 때 쓸 밴드들 ("이 곡의 가사·구간").
     * 없으면 빈 목록이다 (S15P21A204-380-B).
     *
     * <p>{@link #childLinks} 와 같은 방식으로 채워진다 — 기동할 때 빈 목록으로 만들고,
     * 모든 자원이 준비된 **뒤에** `AdminResourceRegistry` 가 선언을 검증해 채운다.
     */
    @Setter
    @Builder.Default
    private List<AdminTimelineAxis> timelineAxes = List.of();

    /**
     * 이 자원의 행에 **행동 타임라인**이 있는가 (S15P21A204-430).
     *
     * <p>{@link #timelineAxes} 와 닮았지만 가리키는 것이 다르다. 그쪽은 <b>관리 자원</b>을
     * 축으로 늘어놓는 것이라 밴드 선언이 필요하지만, 이쪽이 그리는 것은 ClickHouse 의
     * 이벤트 스트림이라 관리 자원이 아니다 — 선언할 축이 없고 <b>이 자원에 그 화면이
     * 있는가</b> 하나만 있으면 된다.
     *
     * <p>그래도 화면이 아니라 서버가 정한다. 화면이 `"user" 면 행동 타임라인` 이라고
     * 알고 있으면 대상이 늘 때마다 템플릿을 고쳐야 한다 — {@code CHILD_LINKS}·
     * {@code TIMELINES} 를 표로 둔 것과 같은 규칙이다.
     */
    @Setter
    @Builder.Default
    private boolean eventTimeline = false;

    public AdminAttribute attribute(String fieldName) {
        return attributes.get(fieldName);
    }

    public List<AdminField> fields() {
        return attributes.values().stream().map(AdminAttribute::getField).toList();
    }

}
