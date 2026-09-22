package com.osundosun.mupid.api.admin.response;

import java.util.List;

import com.osundosun.mupid.api.admin.dto.AdminChildLink;
import com.osundosun.mupid.api.admin.dto.AdminField;
import com.osundosun.mupid.api.admin.dto.AdminTimelineAxis;

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

    List<AdminField> fields,

    /**
     * 이 자원의 행에서 자식 자원으로 건너가는 링크. 없으면 빈 목록이다.
     *
     * <p>화면은 행마다 링크를 그린다 — 앨범 행의 "음악" 이 그것이다. 자식 자원 목록을
     * `filter.<필드>=<이 행의 식별자>` 로 걸어 열므로 새 엔드포인트가 필요 없다.
     */
    List<AdminChildLink> childLinks,

    /**
     * 이 자원의 행을 **시간축 위에 가로로** 그릴 밴드들 (S15P21A204-380-B). 없으면 빈
     * 목록이다.
     *
     * <p>화면은 이 목록이 비어 있지 않으면 "타임라인" 보기를 낸다. 곡(`music`)에만 있다.
     * 각 밴드는 자원 이름·시간 필드·정렬 기준을 담으므로 **화면에 자원 이름이 없다** —
     * 자원이 늘어도 화면은 그대로다.
     */
    List<AdminTimelineAxis> timelineAxes,

    /**
     * 이 자원의 행에서 **행동 타임라인**으로 건너갈 수 있는가 (S15P21A204-430).
     * 회원(`user`)에만 참이다.
     *
     * <p>{@link #timelineAxes} 처럼 목록을 주지 않는 이유는, 그 화면이 그리는 것이
     * 관리 자원이 아니라 ClickHouse 의 이벤트 스트림이기 때문이다 — 늘어놓을 밴드를
     * 자원 이름으로 선언할 수가 없다. 그래도 <b>판단은 서버가 한다</b>: 화면이
     * "회원이면 행동 타임라인" 이라고 알고 있으면 대상이 늘 때마다 템플릿을 고쳐야 한다.
     */
    boolean eventTimeline

) {
}
