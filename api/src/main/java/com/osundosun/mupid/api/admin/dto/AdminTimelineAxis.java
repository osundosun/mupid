package com.osundosun.mupid.api.admin.dto;

import java.util.List;

/**
 * 타임라인에 가로로 늘어놓을 **자원 하나**의 선언.
 *
 * <p>가사(`lyric`)와 구간 분석(`musicSectionAnalysis`)은 컬럼 구성이 다르다 — 가사는
 * `timeMs` 하나뿐이고 구간은 `startTimeMs`~`endTimeMs` 범위에 `sectionLabel`·`index` 가
 * 붙는다. 그래서 "무엇을 시간으로 보고, 무엇으로 고르고, 무엇을 이름으로 보는가"를
 * 데이터로 적어 둔다.
 *
 * <p><b>화면이 자원 이름을 모른다는 규칙을 지키기 위한 것이다.</b> 화면이 `lyric` 이라는
 * 이름과 `timeMs` 컬럼을 알고 있으면 자원이 하나 늘 때마다 템플릿을 고쳐야 한다. 380-A 가
 * 컬럼(`AdminField.relation`)과 링크(`AdminChildLink`)를 데이터로 만든 것과 같은 규칙이다.
 *
 * <h2>시간 값은 어디서 읽는가</h2>
 *
 * 화면은 {@code timeField} 같은 **컬럼 이름을 읽지 않는다.** 구간-분위기처럼 자기 시간
 * 컬럼이 없는 자원은 부모 구간의 시간을 빌려 오는데, 그 조인은 서버가 한다. 그래서 서버가
 * 항목 행에 값을 **정규화한 키로 넣어** 보낸다.
 *
 * <table border="1">
 *   <caption>행에 실리는 시간 키</caption>
 *   <tr><th>키</th><th>무엇</th><th>언제</th></tr>
 *   <tr><td>{@code _time_ms}</td><td>시점</td><td>{@code POINT}</td></tr>
 *   <tr><td>{@code _start_ms}</td><td>구간 시작</td><td>{@code RANGE}</td></tr>
 *   <tr><td>{@code _end_ms}</td><td>구간 끝</td><td>{@code RANGE}</td></tr>
 * </table>
 *
 * 이 키를 380-C 의 드래그가 그대로 쓴다 — 항목이 어느 시간에 있는지 DOM 에서 알 수 있어야
 * 한다는 요구가 그것이다. 화면은 이 값을 {@code data-time-ms}·{@code data-start-ms} 로
 * 옮겨 적는다.
 *
 * @param label       밴드에 보일 이름 ("가사")
 * @param resource    밴드에 늘어놓을 자식 자원 이름 (`lyric` — URL 조각이기도 하다)
 * @param kind        {@link AdminTimelineKind}. 점이냐 구간이냐
 * @param filterField 자식 자원에서 **자기 행을 고르는** 연관 필드 (`music`). 목록 필터로
 *                    나간다 — 자식 목록 API 가 이미 `filter.<필드>=<값>` 을 받으므로
 *                    밴드마다 전용 엔드포인트가 필요하지 않다. 시간을 빌리는 밴드에서는
 *                    **부모(구간)를 가리키는 필드**다
 * @param viaField    시간을 **부모에서 빌려올** 관계 필드 (`musicSectionAnalysis`).
 *                    자기 시간 컬럼이 있으면 null 이다. 구간-분위기가 그렇다 —
 *                    그 표에는 시간 컬럼이 없고 부모 구간에만 있다
 * @param timeField   {@code POINT} 의 시점 컬럼 이름. 아니면 null. **참고용**이다 —
 *                    값은 행의 {@code _time_ms} 로 온다
 * @param startField  {@code RANGE} 의 시작 컬럼 이름. 아니면 null (참고용)
 * @param endField    {@code RANGE} 의 끝 컬럼 이름. 아니면 null (참고용)
 * @param titleFields 항목에 보일 한 줄을 만들 필드들. 순서대로 이어 붙인다
 *                    (구간은 `sectionLabel` + `index`, 가사는 `text`)
 * @param ownsTime    이 밴드가 시간을 **자기 행에서** 읽을 수 있는가.
 *
 *                    <p><b>record 구성 요소여야 한다 — 계산 메서드로 두면 안 된다.</b>
 *                    Jackson 은 record 의 구성 요소만 직렬화하고 `isXxx()` 같은 파생 메서드는
 *                    내보내지 않는다. 처음에 메서드로 만들었다가 **응답에 키가 없었고**,
 *                    화면이 `!undefined` 를 참으로 읽어 **모든 밴드에 "시간은 부모 구간에서"**
 *                    라는 거짓 안내를 그렸다. 실호출로만 드러나는 부류다.
 */
public record AdminTimelineAxis(
    String label,
    String resource,
    AdminTimelineKind kind,
    String filterField,
    String viaField,
    String timeField,
    String startField,
    String endField,
    List<String> titleFields,
    boolean ownsTime
) {

    /**
     * 목록 조회에 넘길 **정렬 필드**.
     *
     * <p>서버가 시간 오름차순으로 주지 않으면 화면이 항목을 다시 정렬해야 하고, 그러면
     * "서버가 준 순서" 와 "보이는 순서" 가 갈라진다. 점이면 시점, 구간이면 시작 시각이다.
     *
     * <p>부모의 시간을 빌리는 밴드는 <b>자기 시간 컬럼이 없어</b> 이 값으로 시간 순을 만들
     * 수 없다 — 그때는 서버가 부모 시각으로 다시 정렬한다(`AdminTimelineServiceImpl`).
     * 정렬 키로는 식별자를 쓴다: 순서가 흔들리지 않게.
     */
    public String sortField() {
        if (kind == AdminTimelineKind.POINT) {
            return timeField;
        }
        return ownsTime ? startField : filterField;
    }

    /**
     * 값이 다 있는 **정상 축**을 만든다. {@code ownsTime} 은 {@code viaField} 에서 유도한다 —
     * 두 값을 따로 넘기면 서로 어긋난 축이 만들어질 수 있고, 그러면 화면의 안내가 거짓이 된다.
     */
    public static AdminTimelineAxis of(
        String label,
        String resource,
        AdminTimelineKind kind,
        String filterField,
        String viaField,
        String timeField,
        String startField,
        String endField,
        List<String> titleFields
    ) {
        return new AdminTimelineAxis(label, resource, kind, filterField, viaField,
            timeField, startField, endField, titleFields, viaField == null);
    }

}
